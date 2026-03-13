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

  (testing "lint-repair-code passes through non-delimiter syntax errors"
    ;; Semantic errors like invalid parameter names are not delimiter errors
    ;; They pass through and will be caught at evaluation/runtime
    (let [ctx {::sut/new-source-code "(defn hello [123] (println name))"}
          result (sut/lint-repair-code ctx)]
      ;; Should not error on non-delimiter syntax issues
      (is (not (::sut/error result)))
      (is (= "(defn hello [123] (println name))" (::sut/new-source-code result)))))

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

;; Tests for partial formatting (cljfmt :partial)

(deftest re-indent-to-column-test
  (testing "re-indent-to-column does nothing at column 1"
    (let [form "(defn foo [x]\n  (+ x 1))"]
      (is (= form (core/re-indent-to-column form 1)))))

  (testing "re-indent-to-column adds indentation to subsequent lines"
    (let [form "(defn foo [x]\n  (+ x 1))"
          result (core/re-indent-to-column form 5)]
      ;; First line unchanged, second line gets 4 spaces of extra indent
      (is (= "(defn foo [x]\n      (+ x 1))" result))))

  (testing "re-indent-to-column preserves blank lines"
    (let [form "(defn foo [x]\n\n  (+ x 1))"
          result (core/re-indent-to-column form 3)]
      (is (= "(defn foo [x]\n\n    (+ x 1))" result))))

  (testing "re-indent-to-column handles single-line form"
    (let [form "(def x 1)"]
      (is (= form (core/re-indent-to-column form 10))))))

(deftest format-form-in-isolation-test
  (testing "format-form-in-isolation formats and re-indents"
    (let [form "(defn  foo[x]   (+ x 1))"
          ;; Use default formatting options
          opts core/default-formatting-options
          result (core/format-form-in-isolation form 1 opts)]
      ;; Should fix spacing issues
      (is (str/includes? result "foo [x]"))
      (is (not (str/includes? result "foo[x]")))))

  (testing "format-form-in-isolation returns original on failure"
    (let [form "(defn foo [x] (+ x 1))"
          ;; Pass nil options to trigger an error
          result (core/format-form-in-isolation form 1 nil)]
      ;; Should return original form on failure
      (is (= form result)))))

(deftest capture-form-position-test
  (testing "capture-form-position records column 1 for top-level form"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
          ctx {::sut/source source
               ::sut/top-level-def-type "defn"
               ::sut/top-level-def-name "example-fn"}
          parsed (sut/parse-source ctx)
          found (sut/find-form parsed)
          result (sut/capture-form-position found)]
      (is (= 1 (::sut/form-col result)))))

  (testing "capture-form-position records column > 1 for nested form"
    ;; defn inside a reader conditional: #?(:clj (defn ...))
    ;; The (defn starts at column 9:
    ;; #?(:clj (defn nested-fn ...
    ;; 123456789
    (let [source "#?(:clj (defn nested-fn [x]\n           (+ x 1)))"
          ctx {::sut/source source
               ::sut/top-level-def-type "defn"
               ::sut/top-level-def-name "nested-fn"}
          parsed (sut/parse-source ctx)
          found (sut/find-form parsed)
          result (sut/capture-form-position found)]
      (is (not (::sut/error found))
          (str "find-form failed: " (::sut/message found)))
      (is (= 9 (::sut/form-col result))
          "Nested form should be at column 9"))))

(deftest capture-form-position-missing-position-test
  (testing "capture-form-position leaves ::form-col absent when position tracking is unavailable"
    (with-redefs [z/position (fn [_] nil)]
      (let [ctx {::sut/zloc :dummy-zloc}
            result (sut/capture-form-position ctx)]
        (is (nil? (::sut/form-col result)))))))

(deftest format-source-partial-skips-whole-file-test
  (testing "format-source skips whole-file formatting when pre-formatted"
    ;; Set cljfmt to :partial
    (config/set-config! *nrepl-client-atom* :cljfmt :partial)
    (try
      ;; Source with intentional bad formatting in the ns form (should be preserved)
      ;; ::pre-formatted? signals that format-new-source-partial already ran
      (let [source "(ns   test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
            ctx {::sut/nrepl-client-atom *nrepl-client-atom*
                 ::sut/output-source source
                 ::sut/pre-formatted? true}
            result (sut/format-source ctx)]
        ;; With ::pre-formatted?, format-source should return the source unchanged
        (is (= source (::sut/output-source result)))
        ;; The extra spaces in ns should be preserved
        (is (str/includes? (::sut/output-source result) "(ns   test.core)")))
      (finally
        ;; Restore default
        (config/set-config! *nrepl-client-atom* :cljfmt true)))))

(deftest format-new-source-partial-no-form-col-test
  (testing "format-new-source-partial passes through unchanged when ::form-col is absent in :partial mode"
    (config/set-config! *nrepl-client-atom* :cljfmt :partial)
    (try
      ;; Source with wrong indentation (3 spaces instead of 2).
      ;; Sanity-check: confirm cljfmt *would* change this if it ran.
      (let [source "(defn foo [x]\n   x)"
            formatted (core/format-form-in-isolation source 1 core/default-formatting-options)]
        (is (not= source formatted)
            "sanity: cljfmt must change this source, otherwise the guard test proves nothing"))
      (let [source "(defn foo [x]\n   x)"
            ctx {::sut/nrepl-client-atom *nrepl-client-atom*
                 ::sut/new-source-code source}
            ;; No ::form-col in ctx — position tracking unavailable
            result (sut/format-new-source-partial ctx)]
        (is (= source (::sut/new-source-code result))
            "source should be unchanged when ::form-col is absent"))
      (finally
        (config/set-config! *nrepl-client-atom* :cljfmt true)))))

(deftest edit-form-pipeline-partial-formatting-test
  (testing "partial formatting only formats the replaced form, not surrounding code"
    ;; Set cljfmt to :partial
    (config/set-config! *nrepl-client-atom* :cljfmt :partial)
    (try
      ;; Create a file with intentionally "bad" formatting in the ns form
      ;; that cljfmt would normally fix (extra spaces)
      (let [test-dir (test-utils/create-test-dir)
            quirky-content (str "(ns   test.core)\n\n"
                                "(defn example-fn\n  [x y]\n  (+ x y))\n\n"
                                "(def   a     1)\n")
            file-path (test-utils/create-and-register-test-file
                       *nrepl-client-atom* test-dir "quirky.clj" quirky-content)
            ;; Replace example-fn with badly-formatted version
            pipeline-result (sut/edit-form-pipeline
                             file-path
                             "example-fn"
                             "defn"
                             "(defn example-fn   [x y]\n  (* x y))"
                             :replace
                             nil
                             {:nrepl-client-atom *nrepl-client-atom*})
            result (sut/format-result pipeline-result)
            file-content (slurp file-path)]
        (is (false? (:error result))
            (str "Pipeline error: " (:message result)))
        ;; The replaced form should be formatted (extra spaces fixed)
        (is (str/includes? file-content "(defn example-fn [x y]")
            "Replaced form should be formatted")
        (is (str/includes? file-content "(* x y)")
            "New implementation should be present")
        ;; The ns form's quirky formatting should be PRESERVED (not reformatted)
        (is (str/includes? file-content "(ns   test.core)")
            "Surrounding ns form formatting should be preserved with :partial")
        ;; The def's quirky formatting should also be PRESERVED
        (is (str/includes? file-content "(def   a     1)")
            "Surrounding def formatting should be preserved with :partial")
        ;; Cleanup
        (test-utils/clean-test-dir test-dir))
      (finally
        (config/set-config! *nrepl-client-atom* :cljfmt true)))))

(deftest edit-form-pipeline-full-formatting-test
  (testing "full formatting (cljfmt true) reformats the entire file"
    ;; Ensure cljfmt is true (default)
    (config/set-config! *nrepl-client-atom* :cljfmt true)
    (let [test-dir (test-utils/create-test-dir)
          quirky-content (str "(ns   test.core)\n\n"
                              "(defn example-fn\n  [x y]\n  (+ x y))\n\n"
                              "(def   a     1)\n")
          file-path (test-utils/create-and-register-test-file
                     *nrepl-client-atom* test-dir "quirky_full.clj" quirky-content)
          pipeline-result (sut/edit-form-pipeline
                           file-path
                           "example-fn"
                           "defn"
                           "(defn example-fn [x y]\n  (* x y))"
                           :replace
                           nil
                           {:nrepl-client-atom *nrepl-client-atom*})
          result (sut/format-result pipeline-result)
          file-content (slurp file-path)]
      (is (false? (:error result))
          (str "Pipeline error: " (:message result)))
      ;; With full formatting, the ns form's extra spaces should be removed
      (is (not (str/includes? file-content "(ns   test.core)"))
          "Full formatting should fix ns form spacing")
      (is (str/includes? file-content "(ns test.core)")
          "Full formatting should normalize ns form")
      ;; Cleanup
      (test-utils/clean-test-dir test-dir))))

(deftest sexp-edit-pipeline-partial-fallback-test
  (testing "sexp-edit-pipeline falls back to full-file formatting when cljfmt is :partial"
    ;; Set cljfmt to :partial
    (config/set-config! *nrepl-client-atom* :cljfmt :partial)
    (try
      (let [test-dir (test-utils/create-test-dir)
            content (str "(ns test.core)\n\n"
                         "(defn example-fn [x y]\n  (+   x   y))\n")
            file-path (test-utils/create-and-register-test-file
                       *nrepl-client-atom* test-dir "sexp_partial.clj" content)
            pipeline-result (sut/sexp-edit-pipeline
                             file-path
                             "(+   x   y)"
                             "(* x y)"
                             :replace
                             false
                             false
                             nil
                             {:nrepl-client-atom *nrepl-client-atom*})
            result (sut/format-result pipeline-result)
            file-content (slurp file-path)]
        (is (false? (:error result))
            (str "Pipeline error: " (:message result)))
        ;; The replacement should be present
        (is (str/includes? file-content "(* x y)")
            "Replacement form should be present")
        ;; Full-file formatting should still run (fallback behavior)
        ;; so the file should be properly formatted
        (is (str/includes? file-content "(defn example-fn [x y]")
            "File should be formatted by full-file cljfmt fallback")
        ;; Cleanup
        (test-utils/clean-test-dir test-dir))
      (finally
        (config/set-config! *nrepl-client-atom* :cljfmt true)))))

(deftest edit-form-pipeline-partial-before-test
  (testing "partial formatting with :before edit type preserves surrounding formatting"
    (config/set-config! *nrepl-client-atom* :cljfmt :partial)
    (try
      (let [test-dir (test-utils/create-test-dir)
            quirky-content (str "(ns   test.core)\n\n"
                                "(defn example-fn\n  [x y]\n  (+ x y))\n")
            file-path (test-utils/create-and-register-test-file
                       *nrepl-client-atom* test-dir "before_partial.clj" quirky-content)
            pipeline-result (sut/edit-form-pipeline
                             file-path
                             "example-fn"
                             "defn"
                             "(defn   helper-fn   [z]\n  (inc z))"
                             :before
                             nil
                             {:nrepl-client-atom *nrepl-client-atom*})
            result (sut/format-result pipeline-result)
            file-content (slurp file-path)]
        (is (false? (:error result))
            (str "Pipeline error: " (:message result)))
        ;; The inserted form should be formatted (extra spaces fixed)
        (is (str/includes? file-content "(defn helper-fn [z]")
            "Inserted form should be formatted")
        ;; The ns form's quirky formatting should be PRESERVED
        (is (str/includes? file-content "(ns   test.core)")
            "Surrounding ns form formatting should be preserved with :partial")
        ;; Original function should still be present
        (is (str/includes? file-content "(defn example-fn")
            "Original form should still exist")
        ;; Cleanup
        (test-utils/clean-test-dir test-dir))
      (finally
        (config/set-config! *nrepl-client-atom* :cljfmt true)))))

(deftest edit-form-pipeline-partial-after-test
  (testing "partial formatting with :after edit type preserves surrounding formatting"
    (config/set-config! *nrepl-client-atom* :cljfmt :partial)
    (try
      (let [test-dir (test-utils/create-test-dir)
            quirky-content (str "(ns   test.core)\n\n"
                                "(defn example-fn\n  [x y]\n  (+ x y))\n")
            file-path (test-utils/create-and-register-test-file
                       *nrepl-client-atom* test-dir "after_partial.clj" quirky-content)
            pipeline-result (sut/edit-form-pipeline
                             file-path
                             "example-fn"
                             "defn"
                             "(defn   helper-fn   [z]\n  (inc z))"
                             :after
                             nil
                             {:nrepl-client-atom *nrepl-client-atom*})
            result (sut/format-result pipeline-result)
            file-content (slurp file-path)]
        (is (false? (:error result))
            (str "Pipeline error: " (:message result)))
        ;; The inserted form should be formatted (extra spaces fixed)
        (is (str/includes? file-content "(defn helper-fn [z]")
            "Inserted form should be formatted")
        ;; The ns form's quirky formatting should be PRESERVED
        (is (str/includes? file-content "(ns   test.core)")
            "Surrounding ns form formatting should be preserved with :partial")
        ;; Original function should still be present
        (is (str/includes? file-content "(defn example-fn")
            "Original form should still exist")
        ;; Cleanup
        (test-utils/clean-test-dir test-dir))
      (finally
        (config/set-config! *nrepl-client-atom* :cljfmt true)))))

(deftest re-indent-to-column-nested-form-test
  (testing "re-indent-to-column correctly indents multi-line form at column > 1"
    (let [;; Simulate a form formatted in isolation that will live at column 8
          ;; (e.g., inside a reader conditional: #?(:clj (defn ...)))
          form "(defn foo [x]\n  (+ x 1)\n  (- x 2))"
          result (core/re-indent-to-column form 8)]
      ;; First line unchanged, subsequent lines get 7 spaces prepended
      (is (= "(defn foo [x]\n         (+ x 1)\n         (- x 2))" result))))

  (testing "format-form-in-isolation formats and re-indents at column > 1"
    (let [;; Multi-line form destined for column 5
          form "(defn foo [x]\n  (+ x 1))"
          opts core/default-formatting-options
          result (core/format-form-in-isolation form 5 opts)]
      ;; First line unchanged, second line gets 4 extra spaces (col 5 = 4-space indent)
      (is (= "(defn foo [x]\n      (+ x 1))" result)))))

(deftest process-config-cljfmt-partial-roundtrip-test
  (testing "process-config preserves :partial cljfmt setting"
    (let [test-dir (System/getProperty "user.dir")
          processed (config/process-config {:cljfmt :partial} test-dir)]
      (is (= :partial (:cljfmt processed))
          ":partial should survive process-config")))

  (testing "process-config coerces truthy cljfmt to boolean"
    (let [test-dir (System/getProperty "user.dir")
          processed (config/process-config {:cljfmt true} test-dir)]
      (is (true? (:cljfmt processed)))))

  (testing "process-config coerces falsy cljfmt to boolean"
    (let [test-dir (System/getProperty "user.dir")
          processed (config/process-config {:cljfmt false} test-dir)]
      (is (false? (:cljfmt processed))))))

;; E2E test reproducing issue #154 exact scenario
;; https://github.com/bhauman/clojure-mcp/issues/154

(deftest issue-154-e2e-test
  (testing "issue #154: replacing bar should NOT change alignment in unrelated defs"
    ;; Exact file content from the issue
    (let [issue-content (str "(ns example.core)\n\n"
                             "(def ob-yes-heavy\n"
                             "  {:orderbook {:yes [[60 200] [55 150]]\n"
                             "               :no  [[40 80]  [35 60]]}})\n\n"
                             "(def ob-empty-no  {:orderbook {:yes [[60 100]] :no []}})\n"
                             "(def ob-empty     {:orderbook {:yes [] :no []}})\n\n"
                             "(defn foo [x] (+ x 1))\n\n"
                             "(defn bar [x] (+ x 2))\n")]

      ;; Test with :partial — only bar should change
      (config/set-config! *nrepl-client-atom* :cljfmt :partial)
      (try
        (let [test-dir (test-utils/create-test-dir)
              file-path (test-utils/create-and-register-test-file
                         *nrepl-client-atom* test-dir "issue154.clj" issue-content)
              pipeline-result (sut/edit-form-pipeline
                               file-path
                               "bar"
                               "defn"
                               "(defn bar [x] (+ x 3))"
                               :replace
                               nil
                               {:nrepl-client-atom *nrepl-client-atom*})
              result (sut/format-result pipeline-result)
              file-content (slurp file-path)]
          (is (false? (:error result))
              (str "Pipeline error: " (:message result)))
          ;; The replaced form should have the new implementation
          (is (str/includes? file-content "(defn bar [x] (+ x 3))")
              "bar should have new implementation")
          ;; Alignment spacing in unrelated defs must be PRESERVED
          (is (str/includes? file-content ":no  [[40 80]  [35 60]]")
              "Alignment in ob-yes-heavy :no should be preserved (double space before [[40)")
          (is (str/includes? file-content "(def ob-empty-no  {:orderbook")
              "Double space after ob-empty-no should be preserved")
          (is (str/includes? file-content "(def ob-empty     {:orderbook")
              "Alignment spaces in ob-empty should be preserved")
          ;; foo should be completely untouched
          (is (str/includes? file-content "(defn foo [x] (+ x 1))")
              "foo should be untouched")
          ;; ns should be untouched
          (is (str/includes? file-content "(ns example.core)")
              "ns should be untouched")
          ;; Cleanup
          (test-utils/clean-test-dir test-dir))
        (finally
          (config/set-config! *nrepl-client-atom* :cljfmt true)))))

  (testing "issue #154 contrast: cljfmt true DOES destroy alignment (the bug)"
    ;; Same file, but with cljfmt true — shows the problem the issue reports
    (config/set-config! *nrepl-client-atom* :cljfmt true)
    (let [issue-content (str "(ns example.core)\n\n"
                             "(def ob-yes-heavy\n"
                             "  {:orderbook {:yes [[60 200] [55 150]]\n"
                             "               :no  [[40 80]  [35 60]]}})\n\n"
                             "(def ob-empty-no  {:orderbook {:yes [[60 100]] :no []}})\n"
                             "(def ob-empty     {:orderbook {:yes [] :no []}})\n\n"
                             "(defn foo [x] (+ x 1))\n\n"
                             "(defn bar [x] (+ x 2))\n")
          test-dir (test-utils/create-test-dir)
          file-path (test-utils/create-and-register-test-file
                     *nrepl-client-atom* test-dir "issue154_full.clj" issue-content)
          pipeline-result (sut/edit-form-pipeline
                           file-path
                           "bar"
                           "defn"
                           "(defn bar [x] (+ x 3))"
                           :replace
                           nil
                           {:nrepl-client-atom *nrepl-client-atom*})
          result (sut/format-result pipeline-result)
          file-content (slurp file-path)]
      (is (false? (:error result))
          (str "Pipeline error: " (:message result)))
      ;; With full formatting, alignment IS destroyed (this is the bug #154 reports)
      (is (not (str/includes? file-content "(def ob-empty-no  {:orderbook"))
          "Full formatting destroys double-space alignment in ob-empty-no")
      (is (not (str/includes? file-content "(def ob-empty     {:orderbook"))
          "Full formatting destroys alignment spaces in ob-empty")
      ;; Cleanup
      (test-utils/clean-test-dir test-dir))))

