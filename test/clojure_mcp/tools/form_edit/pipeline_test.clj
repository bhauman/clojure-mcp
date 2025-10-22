(ns clojure-mcp.tools.form-edit.pipeline-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure-mcp.config :as config]
   [clojure-mcp.tools.form-edit.pipeline :as sut]
   [clojure-mcp.tools.form-edit.core :as core]
   [clojure-mcp.tools.test-utils :as test-utils]
   [rewrite-clj.zip :as z]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Test fixtures
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-file* nil)
(def ^:dynamic *nrepl-client-atom* nil)

(defn create-test-files-fixture [f]
  (binding [*nrepl-client-atom* test-utils/*nrepl-client-atom*]
    (let [test-dir (clojure-mcp.tools.test-utils/create-test-dir)
          test-file-content (str "(ns test.core)\n\n"
                                 "(defn example-fn\n  \"Original docstring\"\n  [x y]\n  (+ x y))\n\n"
                                 "(def a 1)\n\n"
                                 "(comment\n  (example-fn 1 2))\n\n"
                                 ";; Test comment\n;; spans multiple lines")
          test-file-path (clojure-mcp.tools.test-utils/create-and-register-test-file
                          *nrepl-client-atom*
                          test-dir
                          "test.clj"
                          test-file-content)]
      (binding [*test-dir* test-dir
                *test-file* (io/file test-file-path)]
        (config/set-config! test-utils/*nrepl-client-atom* :nrepl-user-dir test-dir)
        (try
          (f)
          (finally
            (clojure-mcp.tools.test-utils/clean-test-dir test-dir)))))))

(use-fixtures :once test-utils/test-nrepl-fixture)
(use-fixtures :each create-test-files-fixture)

;; Test helper functions
(defn get-file-path []
  (.getAbsolutePath *test-file*))

;; Tests for pipeline functions

(deftest thread-ctx-test
  (testing "thread-ctx passes context through functions"
    (let [ctx {:a 1}
          result (sut/thread-ctx ctx #(assoc % :b 2) #(assoc % :c 3))]
      (is (= 1 (:a result)))
      (is (= 2 (:b result)))
      (is (= 3 (:c result)))))

  (testing "thread-ctx short-circuits on error"
    (let [ctx {:a 1}
          result (sut/thread-ctx ctx
                                 #(assoc % :b 2)
                                 #(assoc % ::sut/error true ::sut/message "Error")
                                 #(assoc % :c 3))]
      (is (= 1 (:a result)))
      (is (= 2 (:b result)))
      (is (true? (::sut/error result)))
      (is (= "Error" (::sut/message result)))
      (is (nil? (:c result)) "Should not have run the third function"))))

(deftest validate-form-type-test
  (testing "validate-form-type allows regular form types"
    (let [ctx {::sut/top-level-def-type "defn"}
          result (sut/validate-form-type ctx)]
      (is (= ctx result))))

  (testing "validate-form-type rejects 'comment' form type"
    (let [ctx {::sut/top-level-def-type "comment"}
          result (sut/validate-form-type ctx)]
      (is (true? (::sut/error result)))
      (is (string? (::sut/message result)))
      (is (str/includes? (::sut/message result) "not supported for definition editing")))))

(deftest load-source-test
  (testing "load-source loads file content"
    (let [ctx {::sut/file-path (get-file-path)}
          result (sut/load-source ctx)]
      (is (string? (::sut/source result)))
      (is (= (::sut/source result) (::sut/old-content result)))
      (is (str/includes? (::sut/source result) "(defn example-fn"))))

  (testing "load-source returns error for non-existent file"
    (let [ctx {::sut/file-path "/non-existent-path.clj"}
          result (sut/load-source ctx)]
      (is (true? (::sut/error result)))
      (is (string? (::sut/message result)))
      (is (str/includes? (::sut/message result) "not found")))))

(deftest parse-source-test
  (testing "parse-source creates zipper"
    (let [ctx {::sut/source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"}
          result (sut/parse-source ctx)]
      (is (some? (::sut/zloc result)))
      ;; Symbol equality check in tests can be tricky - just check string representation
      (is (= "test.core" (str (-> result ::sut/zloc z/down z/right z/sexpr)))))))

(deftest enhance-defmethod-name-test
  (testing "enhance-defmethod-name extracts dispatch value from replacement content"
    (let [ctx {::sut/top-level-def-type "defmethod"
               ::sut/top-level-def-name "area"
               ::sut/new-source-code "(defmethod area :rectangle [rect] (* (:width rect) (:height rect)))"}
          result (sut/enhance-defmethod-name ctx)]
      (is (= "area :rectangle" (::sut/top-level-def-name result)))
      (is (= "defmethod" (::sut/top-level-def-type result)))
      (is (= "(defmethod area :rectangle [rect] (* (:width rect) (:height rect)))" (::sut/new-source-code result)))))

  (testing "enhance-defmethod-name preserves compound name if already provided"
    (let [ctx {::sut/top-level-def-type "defmethod"
               ::sut/top-level-def-name "area :circle"
               ::sut/new-source-code "(defmethod area :rectangle [rect] (* (:width rect) (:height rect)))"}
          result (sut/enhance-defmethod-name ctx)]
      (is (= "area :circle" (::sut/top-level-def-name result)))
      (is (= "defmethod" (::sut/top-level-def-type result)))
      (is (= "(defmethod area :rectangle [rect] (* (:width rect) (:height rect)))" (::sut/new-source-code result)))))

  (testing "enhance-defmethod-name doesn't modify non-defmethod forms"
    (let [ctx {::sut/top-level-def-type "defn"
               ::sut/top-level-def-name "example-fn"
               ::sut/new-source-code "(defn example-fn [x] (* x 2))"}
          result (sut/enhance-defmethod-name ctx)]
      (is (= "example-fn" (::sut/top-level-def-name result)))
      (is (= "defn" (::sut/top-level-def-type result)))
      (is (= "(defn example-fn [x] (* x 2))" (::sut/new-source-code result)))))

  (testing "enhance-defmethod-name handles malformed defmethod content gracefully"
    (let [ctx {::sut/top-level-def-type "defmethod"
               ::sut/top-level-def-name "area"
               ::sut/new-source-code "(defmethod area)"}
          result (sut/enhance-defmethod-name ctx)]
      (is (= "area" (::sut/top-level-def-name result)))
      (is (= "defmethod" (::sut/top-level-def-type result)))
      (is (= "(defmethod area)" (::sut/new-source-code result))))))

(deftest find-form-test
  (testing "find-form locates form"
    (let [ctx {::sut/source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
               ::sut/top-level-def-type "defn"
               ::sut/top-level-def-name "example-fn"}
          parsed (sut/parse-source ctx)
          result (sut/find-form parsed)]
      (is (some? (::sut/zloc result)))
      (is (= 'defn (-> result ::sut/zloc z/down z/sexpr)))))

  (testing "find-form locates private defn and def forms"
    (let [ctx {::sut/source "(ns test.core)\n\n(defn- hidden [] :secret)\n\n(def- secret 1)"
               ::sut/top-level-def-type "defn"
               ::sut/top-level-def-name "hidden"}
          parsed (sut/parse-source ctx)
          result (sut/find-form parsed)]
      (is (some? (::sut/zloc result)))
      (is (= 'defn- (-> result ::sut/zloc z/down z/sexpr))))

    (let [ctx {::sut/source "(ns test.core)\n\n(defn- hidden [] :secret)\n\n(def- secret 1)"
               ::sut/top-level-def-type "def"
               ::sut/top-level-def-name "secret"}
          parsed (sut/parse-source ctx)
          result (sut/find-form parsed)]
      (is (some? (::sut/zloc result)))
      (is (= 'def- (-> result ::sut/zloc z/down z/sexpr)))))

  (testing "find-form returns error for non-existent form"
    (let [ctx {::sut/source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
               ::sut/top-level-def-type "defn"
               ::sut/top-level-def-name "non-existent"}
          parsed (sut/parse-source ctx)
          result (sut/find-form parsed)]
      (is (true? (::sut/error result)))
      (is (str/includes? (::sut/message result) "Could not find form")))))

(deftest edit-form-test
  (testing "edit-form replaces form"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
          ctx {::sut/source source
               ::sut/top-level-def-type "defn"
               ::sut/top-level-def-name "example-fn"
               ::sut/new-source-code "(defn example-fn [x y]\n  (* x y))"
               ::sut/edit-type :replace}
          parsed (sut/parse-source ctx)
          found (sut/find-form parsed)
          result (sut/edit-form found)
          edited-str (z/root-string (::sut/zloc result))]
      (is (some? (::sut/zloc result)))
      (is (str/includes? edited-str "(defn example-fn [x y]\n  (* x y))"))
      (is (not (str/includes? edited-str "(defn example-fn [x y]\n  (+ x y))"))))))

(deftest format-source-test
  (testing "format-source formats the source code"
    ;; Manual setup to avoid dependency on zloc->output-source
    (let [source "(ns test.core)\n\n(defn  example-fn[x y]   (+ x y))"
          ctx {::sut/nrepl-client-atom *nrepl-client-atom*
               ::sut/output-source source} ;; Directly use the source as output source
          result (sut/format-source ctx)]
      (is (string? (::sut/output-source result)))
      (is (not (str/includes? (::sut/output-source result) "  example-fn[x y]")))
      (is (str/includes? (::sut/output-source result) "example-fn [x y]")))))

(deftest generate-diff-test
  (testing "generate-diff creates diff when content changes"
    (let [ctx {::sut/old-content "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
               ::sut/output-source "(ns test.core)\n\n(defn example-fn [x y]\n  (* x y))"}
          result (sut/generate-diff ctx)]
      (is (string? (::sut/diff result)))
      (is (str/includes? (::sut/diff result) "(+ x y)"))
      (is (str/includes? (::sut/diff result) "(* x y)"))))

  (testing "generate-diff returns empty string for identical content"
    (let [content "(ns test.core)"
          ctx {::sut/old-content content
               ::sut/output-source content}
          result (sut/generate-diff ctx)]
      (is (= "" (::sut/diff result))))))

(deftest determine-file-type-test
  (testing "determine-file-type returns 'update' for existing file"
    (let [ctx {::sut/file-path (get-file-path)}
          result (sut/determine-file-type ctx)]
      (is (= "update" (::sut/type result)))))

  (testing "determine-file-type returns 'create' for new file"
    (let [ctx {::sut/file-path (str (get-file-path) ".new")}
          result (sut/determine-file-type ctx)]
      (is (= "create" (::sut/type result))))))

(deftest format-result-test
  (testing "format-result formats success context"
    (let [ctx {::sut/offsets [10 20]
               ::sut/output-source "formatted content"
               ::sut/diff "diff content"}
          result (sut/format-result ctx)]
      (is (false? (:error result)))
      (is (= [10 20] (:offsets result)))
      (is (= ["formatted content"] (:result result)))
      (is (= "diff content" (:diff result)))))

  (testing "format-result formats error context"
    (let [ctx {::sut/error true
               ::sut/message "Error message"}
          result (sut/format-result ctx)]
      (is (true? (:error result)))
      (is (= "Error message" (:message result))))))

;; Integration tests for pipelines

(deftest lint-repair-code-test
  (testing "lint-repair-code repairs missing closing delimiter"
    (let [ctx {::sut/new-source-code "(defn hello [name] (println name)"}
          result (sut/lint-repair-code ctx)]
      (is (not (::sut/error result)))
      (is (true? (::sut/repaired result)))
      (is (= "(defn hello [name] (println name)" (::sut/original-code result)))
      (is (= "(defn hello [name] (println name))" (::sut/new-source-code result)))))

  (testing "lint-repair-code repairs extra closing delimiter"
    (let [ctx {::sut/new-source-code "(defn hello [name] (println name)))"}
          result (sut/lint-repair-code ctx)]
      (is (not (::sut/error result)))
      (is (true? (::sut/repaired result)))
      (is (= "(defn hello [name] (println name)))" (::sut/original-code result)))
      (is (= "(defn hello [name] (println name))" (::sut/new-source-code result)))))

  (testing "lint-repair-code handles non-repairable syntax errors"
    (let [ctx {::sut/new-source-code "(defn hello [123] (println name))"}
          result (sut/lint-repair-code ctx)]
      (is (::sut/error result))
      (is (= :lint-failure (::sut/error result)))
      (is (str/includes? (::sut/message result) "Syntax errors detected"))))

  (testing "lint-repair-code handles non-repairable delimiter errors"
    (let [ctx {::sut/new-source-code "(defn hello [name] (println \"Hello)"}
          result (sut/lint-repair-code ctx)]
      (is (::sut/error result))
      (is (= :lint-failure (::sut/error result)))
      (is (str/includes? (::sut/message result) "Delimiter errors detected"))))

  (testing "lint-repair-code handles well-formed code"
    (let [ctx {::sut/new-source-code "(defn hello [name] (println name))"}
          result (sut/lint-repair-code ctx)]
      (is (not (::sut/error result)))
      (is (nil? (::sut/repaired result)))
      (is (= "(defn hello [name] (println name))" (::sut/new-source-code result))))))

(deftest edit-form-pipeline-test
  (testing "edit-form-pipeline edits form in file"
    (let [file-path (get-file-path)
          pipeline-result (sut/edit-form-pipeline
                           file-path
                           "example-fn"
                           "defn"
                           "(defn example-fn [x y]\n  (* x y))"
                           :replace
                           nil
                           *nrepl-client-atom*)
          result (sut/format-result pipeline-result)
          file-content (slurp file-path)]
      (is (false? (:error result))
          (str "Pipeline error: " (:message result)))
      (is (some? (:offsets result)))
      (is (str/includes? file-content "(defn example-fn")
          "File should contain updated function")
      (is (str/includes? file-content "(* x y)")
          "File should include the updated expression")
      (is (not (str/includes? file-content "(+ x y)"))
          "Original implementation should be replaced"))))

(deftest edit-form-pipeline-comment-validation-test
  (testing "edit-form-pipeline rejects comment form type"
    (let [file-path (get-file-path)
          pipeline-result (sut/edit-form-pipeline
                           file-path
                           "test-comment"
                           "comment"
                           "(comment some test comment)"
                           :replace
                           nil
                           *nrepl-client-atom*)]
      (is (true? (::sut/error pipeline-result)))
      (is (string? (::sut/message pipeline-result)))
      (is (str/includes? (::sut/message pipeline-result) "not supported for definition editing")))))

(deftest docstring-edit-pipeline-test
  (testing "docstring-edit-pipeline updates docstring"
    (let [file-path (get-file-path)
          pipeline-result (sut/docstring-edit-pipeline
                           file-path
                           "example-fn"
                           "defn"
                           "Updated docstring"
                           *nrepl-client-atom*)
          result (sut/format-result pipeline-result)
          file-content (slurp file-path)]
      (is (false? (:error result))
          (str "Pipeline error: " (:message result)))
      (is (some? (:offsets result)))
      (is (str/includes? file-content "Updated docstring")
          "File should contain updated docstring")
      (is (not (str/includes? file-content "Original docstring"))
          "Original docstring should be replaced"))))

(deftest docstring-edit-pipeline-comment-validation-test
  (testing "docstring-edit-pipeline rejects comment form type"
    (let [file-path (get-file-path)
          pipeline-result (sut/docstring-edit-pipeline
                           file-path
                           "test-comment"
                           "comment"
                           "Updated docstring"
                           *nrepl-client-atom*)]
      (is (true? (::sut/error pipeline-result)))
      (is (string? (::sut/message pipeline-result)))
      (is (str/includes? (::sut/message pipeline-result) "not supported for definition editing")))))

(deftest comment-block-edit-pipeline-test
  (testing "comment-block-edit-pipeline edits line comment"
    (let [file-path (get-file-path)
          file-content-before (slurp file-path)
          new-comment ";; Updated comment\n;; with new content"
          pipeline-result (sut/comment-block-edit-pipeline
                           file-path
                           "Test comment" ; Part of the exact line in the test file
                           new-comment
                           *nrepl-client-atom*)
          result (sut/format-result pipeline-result)
          file-content-after (slurp file-path)]
      (is (false? (:error result))
          (str "Pipeline error: " (:message result)))
      (is (some? (:offsets result)))

      ;; Test for exact match of the comment content (not just includes)
      (is (.contains file-content-after ";; Updated comment")
          "File should contain exact comment text")
      (is (.contains file-content-after ";; with new content")
          "File should contain exact comment text")

      ;; Make sure we're not serializing zipper data by checking for zipper-related strings
      (is (not (.contains file-content-after "rewrite-clj"))
          "File should not contain serialized zipper data")
      (is (not (.contains file-content-after "clj_string"))
          "File should not contain serialized zipper data")
      (is (not (.contains file-content-after "clj_node"))
          "File should not contain serialized zipper data")

      ;; Test that there are no braces/brackets in comments (indicating serialized data structures)
      (is (not (re-find #";.*\{.*\}" file-content-after))
          "Comments should not contain curly braces from serialized data structures")
      (is (not (re-find #";.*\[.*\]" file-content-after))
          "Comments should not contain square brackets from serialized data structures")

      ;; Check that the original comment is replaced
      (is (not (.contains file-content-after "Test comment"))
          "Original comment should be replaced")))

  (testing "comment-block-edit-pipeline edits comment form"
    (let [file-path (get-file-path)
          new-comment-form "(comment\n  (example-fn 10 20))"
          pipeline-result (sut/comment-block-edit-pipeline
                           file-path
                           "(example-fn 1 2)" ; This is inside the comment form
                           new-comment-form
                           *nrepl-client-atom*)
          result (sut/format-result pipeline-result)
          file-content (slurp file-path)]
      (is (false? (:error result))
          (str "Pipeline error: " (:message result)))
      (is (some? (:offsets result)))

      ;; Test for exact match of the comment content
      (is (.contains file-content "(comment\n  (example-fn 10 20))")
          "File should contain exactly the new comment form")

      ;; Make sure we're not serializing zipper data
      (is (not (.contains file-content "rewrite-clj"))
          "File should not contain serialized zipper data")
      (is (not (.contains file-content "clj_node"))
          "File should not contain serialized zipper data")
      (is (not (.contains file-content "clj_string"))
          "File should not contain serialized zipper data")

      ;; Test for serialized data structures in comment forms
      (is (not (re-find #"\(comment.*\{.*\}" file-content))
          "Comment form should not contain curly braces from serialized data structures")
      (is (not (re-find #"\(comment.*\[.*\]" file-content))
          "Comment form should not contain square brackets from serialized data structures")

      ;; Check that the original comment content is replaced
      (is (not (.contains file-content "(example-fn 1 2)"))
          "Original comment should be replaced")))

  (testing "comment-block-edit-pipeline correctly handles end-of-file comments"
    (let [file-path (get-file-path)
          ;; Add a comment at the end of file
          _ (test-utils/modify-test-file *nrepl-client-atom* file-path
                                         (str (slurp file-path) "\n\n;; End of file comment")
                                         :update-timestamp? true)
          new-comment ";; Updated EOF comment"
          pipeline-result (sut/comment-block-edit-pipeline
                           file-path
                           "End of file comment"
                           new-comment
                           *nrepl-client-atom*)
          result (sut/format-result pipeline-result)
          file-content (slurp file-path)]
      (is (false? (:error result))
          (str "Pipeline error: " (:message result)))
      (is (some? (:offsets result)))

      ;; Check for exact match of the new comment
      (is (.contains file-content ";; Updated EOF comment")
          "File should contain the updated EOF comment")

      ;; Make sure we're not serializing zipper data
      (is (not (.contains file-content "rewrite-clj"))
          "File should not contain serialized zipper data")
      (is (not (.contains file-content "clj_string"))
          "File should not contain serialized zipper data")
      (is (not (.contains file-content "clj_node"))
          "File should not contain serialized zipper data")

      ;; Test for serialized data structures in EOF comments
      (is (not (re-find #";.*\{.*\}" file-content))
          "EOF comments should not contain curly braces from serialized data structures")
      (is (not (re-find #";.*\[.*\]" file-content))
          "EOF comments should not contain square brackets from serialized data structures")

      ;; Verify the original is gone
      (is (not (.contains file-content "End of file comment"))
          "Original EOF comment should be replaced"))))

