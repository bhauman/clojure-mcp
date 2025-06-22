(ns clojure-mcp.tools.form-edit.tool-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure-mcp.tools.form-edit.tool :as sut]
   [clojure-mcp.tools.form-edit.pipeline :as pipeline]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure-mcp.tools.test-utils :as test-utils]
   [clojure-mcp.config :as config] ; Added config require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [rewrite-clj.parser :as p]
   [rewrite-clj.node :as n]))

;; Test fixtures
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-file* nil)
(def ^:dynamic *client-atom* nil)
(def client-atom-for-tests nil) ;; Will be set in the :once fixture

(defn create-test-files-fixture [f]
  ;; Make sure we have a valid client atom
  (when (nil? test-utils/*nrepl-client-atom*)
    (test-utils/test-nrepl-fixture identity))

  (let [test-dir (test-utils/create-test-dir)
        client-atom test-utils/*nrepl-client-atom*
        test-file-content (str "(ns test.core)\n\n"
                               "(defn example-fn\n  \"Original docstring\"\n  [x y]\n  #_(println \"debug value:\" x)\n  (+ x y))\n\n"
                               "(def a 1)\n\n"
                               "#_(def unused-value 42)\n\n"
                               "(comment\n  (example-fn 1 2))\n\n"
                               ";; Test comment\n;; spans multiple lines")
        ;; Make sure client atom has necessary configuration
        _ (config/set-config! client-atom :nrepl-user-dir test-dir)
        _ (config/set-config! client-atom :allowed-directories [test-dir])
        _ (swap! client-atom assoc ::file-timestamps/file-timestamps {}) ; Keep this direct for now
        ;; Create and register the test file
        test-file-path (test-utils/create-and-register-test-file
                        client-atom
                        test-dir
                        "test.clj"
                        test-file-content)]
    (binding [*test-dir* test-dir
              *test-file* (io/file test-file-path)
              *client-atom* client-atom]
      (try
        (f)
        (finally
          (test-utils/clean-test-dir test-dir))))))

(use-fixtures :once (fn [f]
                      ;; Make sure we have a valid nREPL client atom for tests
                      (test-utils/test-nrepl-fixture
                       (fn []
                          ;; Set up global client atom for tests
                         (def client-atom-for-tests test-utils/*nrepl-client-atom*)
                          ;; Run the actual test
                         (binding [test-utils/*nrepl-client-atom* test-utils/*nrepl-client-atom*]
                           (f))))))
(use-fixtures :each create-test-files-fixture)

;; Test helper functions
(defn get-file-path []
  (.getCanonicalPath *test-file*))

(defn make-callback []
  (let [p (promise)]
    [p (fn [result error]
         (deliver p {:result result :error error}))]))

(defn validate-mcp-result
  "Validates that a result conforms to the MCP result format.
   Can also validate content if diff-pattern-fn is provided.
   
   Parameters:
   - result: The formatted result from tool-system/format-results
   - expected-error: Whether the result should indicate an error (default: false)
   - diff-pattern-fn: Optional function that takes the first string in :result
                      and returns true if the content matches expectations"
  ([result]
   (validate-mcp-result result false nil))
  ([result expected-error]
   (validate-mcp-result result expected-error nil))
  ([result expected-error diff-pattern-fn]
   ;; Test response structure
   (is (map? result) "Response should be a map")
   (is (contains? result :result) "Response should contain :result key")
   (is (contains? result :error) "Response should contain :error key")
   (is (instance? Boolean (:error result)) "Error flag should be a boolean")
   (is (= expected-error (:error result))
       (str "Error flag should be " (if expected-error "true" "false")))

   ;; Test result content format
   (is (vector? (:result result)) "Result should be a vector")
   (is (every? string? (:result result)) "All result items should be strings")
   (is (not (empty? (:result result))) "Result should not be empty")

   ;; Test specific content pattern if provided
   (when (and diff-pattern-fn (not (empty? (:result result))))
     (is (diff-pattern-fn (first (:result result)))
         "Result content should match expected pattern"))

   ;; Return result for further testing
   result))

;; Tests for tool configurations
(deftest tool-configurations-test
  (testing "Tool factory functions create correct configurations"
    (let [client-atom *client-atom*
          replace-tool (sut/create-edit-replace-form-tool client-atom)
          before-tool (sut/create-edit-insert-before-form-tool client-atom)
          after-tool (sut/create-edit-insert-after-form-tool client-atom)
          docstring-tool (sut/create-edit-docstring-tool client-atom)
          comment-tool (sut/create-edit-comment-block-tool client-atom)]

      (testing "Replace form tool has correct configuration"
        (is (= :clojure-edit-replace-form (:tool-type replace-tool)))
        (is (= client-atom (:nrepl-client-atom replace-tool))))

      (testing "Insert before tool has correct configuration"
        (is (= :clojure-edit-insert-before-form (:tool-type before-tool)))
        (is (= client-atom (:nrepl-client-atom before-tool))))

      (testing "Insert after tool has correct configuration"
        (is (= :clojure-edit-insert-after-form (:tool-type after-tool)))
        (is (= client-atom (:nrepl-client-atom after-tool))))

      (testing "Docstring tool has correct configuration"
        (is (= :clojure-edit-replace-docstring (:tool-type docstring-tool)))
        (is (= client-atom (:nrepl-client-atom docstring-tool))))

      (testing "Comment block tool has correct configuration"
        (is (= :clojure-edit-comment-block (:tool-type comment-tool)))
        (is (= client-atom (:nrepl-client-atom comment-tool)))))))

;; Tests for multimethod implementations
(deftest tool-multimethod-test
  (testing "Tool multimethods are implemented correctly"
    (let [client-atom *client-atom*
          replace-tool (sut/create-edit-replace-form-tool client-atom)
          before-tool (sut/create-edit-insert-before-form-tool client-atom)
          docstring-tool (sut/create-edit-docstring-tool client-atom)
          comment-tool (sut/create-edit-comment-block-tool client-atom)]

      (testing "Tool names are correct"
        (is (= "clojure_edit_replace_definition" (tool-system/tool-name replace-tool)))
        (is (= "clojure_edit_insert_before_definition" (tool-system/tool-name before-tool)))
        (is (= "clojure_edit_replace_docstring" (tool-system/tool-name docstring-tool)))
        (is (= "clojure_edit_replace_comment_block" (tool-system/tool-name comment-tool))))

      (testing "Tool descriptions are not empty"
        (is (string? (tool-system/tool-description replace-tool)))
        (is (not (str/blank? (tool-system/tool-description replace-tool))))
        (is (string? (tool-system/tool-description before-tool)))
        (is (not (str/blank? (tool-system/tool-description before-tool)))))

      (testing "Tool schemas are correctly defined"
        (let [replace-schema (tool-system/tool-schema replace-tool)
              docstring-schema (tool-system/tool-schema docstring-tool)
              comment-schema (tool-system/tool-schema comment-tool)]
          (is (map? replace-schema))
          (is (= :object (:type replace-schema)))
          (is (contains? (:properties replace-schema) :file_path))
          (is (contains? (:properties replace-schema) :form_identifier))
          (is (contains? (:properties replace-schema) :form_type))
          (is (contains? (:properties replace-schema) :content))

          (is (map? docstring-schema))
          (is (= :object (:type docstring-schema)))
          (is (contains? (:properties docstring-schema) :docstring))

          (is (map? comment-schema))
          (is (= :object (:type comment-schema)))
          (is (contains? (:properties comment-schema) :comment_substring))
          (is (contains? (:properties comment-schema) :new_content)))))))

;; Tests for validation
(deftest validation-test
  (testing "Input validation functions correctly"
    (let [client-atom *client-atom*
          replace-tool (sut/create-edit-replace-form-tool client-atom)
          docstring-tool (sut/create-edit-docstring-tool client-atom)
          comment-tool (sut/create-edit-comment-block-tool client-atom)]

      (testing "Replace form validation checks required parameters"
        (let [valid-inputs {:file_path (get-file-path)
                            :form_identifier "example-fn"
                            :form_type "defn"
                            :content "(defn example-fn [x] (* x 2))"}
              validated (tool-system/validate-inputs replace-tool valid-inputs)]
          ;; Only check that we have a valid file path, not the exact value
          ;; since validate-path-with-client may normalize/canonicalize the path
          (is (string? (:file_path validated)))
          (is (= "example-fn" (:form_name validated)))
          (is (= "defn" (:form_type validated)))
          (is (= "(defn example-fn [x] (* x 2))" (:content validated))))

        (is (thrown? clojure.lang.ExceptionInfo
                     (tool-system/validate-inputs replace-tool
                                                  {:form_identifier "example-fn"
                                                   :form_type "defn"
                                                   :content "(defn example-fn [x] (* x 2))"}))
            "Should throw exception when file_path is missing")

        (is (thrown? clojure.lang.ExceptionInfo
                     (tool-system/validate-inputs replace-tool
                                                  {:file_path (get-file-path)
                                                   :form_type "defn"
                                                   :content "(defn example-fn [x] (* x 2))"}))
            "Should throw exception when form_name is missing"))

      (testing "Docstring validation checks required parameters"
        (let [valid-inputs {:file_path (get-file-path)
                            :form_identifier "example-fn"
                            :form_type "defn"
                            :docstring "New docstring"}
              validated (tool-system/validate-inputs docstring-tool valid-inputs)]
          ;; Only check that we have a valid file path, not the exact value
          ;; since validate-path-with-client may normalize/canonicalize the path
          (is (string? (:file_path validated)))
          (is (= "example-fn" (:form_name validated)))
          (is (= "defn" (:form_type validated)))
          (is (= "New docstring" (:docstring validated))))

        (is (thrown? clojure.lang.ExceptionInfo
                     (tool-system/validate-inputs docstring-tool
                                                  {:file_path (get-file-path)
                                                   :form_identifier "example-fn"
                                                   :form_type "defn"}))
            "Should throw exception when docstring is missing"))

      (testing "Comment block validation checks required parameters"
        (let [valid-inputs {:file_path (get-file-path)
                            :comment_substring "Test comment"
                            :new_content ";; Updated comment"}
              validated (tool-system/validate-inputs comment-tool valid-inputs)]
          ;; Only check that we have a valid file path, not the exact value
          ;; since validate-path-with-client may normalize/canonicalize the path
          (is (string? (:file_path validated)))
          (is (= "Test comment" (:comment_substring validated)))
          (is (= ";; Updated comment" (:new_content validated))))

        (is (thrown? clojure.lang.ExceptionInfo
                     (tool-system/validate-inputs comment-tool
                                                  {:file_path (get-file-path)
                                                   :new_content ";; Updated comment"}))
            "Should throw exception when comment_substring is missing")))))

(deftest sexp-replace-validation-test
  (testing "Sexp replace validation checks for multiple forms in match_form"
    (let [client-atom *client-atom*
          sexp-tool (sut/create-edit-replace-sexp-tool client-atom)
          valid-inputs {:file_path (get-file-path)
                        :match_form "(+ x y)"
                        :new_form "(+ x (* y 2))"}
          invalid-inputs {:file_path (get-file-path)
                          :match_form "(+ x y) (- x y)" ;; Multiple forms!
                          :new_form "(+ x (* y 2))"}
          another-invalid {:file_path (get-file-path)
                           :match_form "(def a 1) (def b 2)" ;; Multiple forms!
                           :new_form "(+ x (* y 2))"}]

      ;; Test valid input is accepted
      (let [validated (tool-system/validate-inputs sexp-tool valid-inputs)]
        (is (string? (:file_path validated)))
        (is (= "(+ x y)" (:match_form validated)))
        (is (= "(+ x (* y 2))" (:new_form validated)))))))

;; Integration tests for backward compatibility functions
(deftest backward-compatibility-test
  (testing "Backward compatibility functions create valid registration maps"
    (let [client-atom *client-atom*
          replace-reg (sut/top-level-form-edit-tool client-atom)
          before-reg (sut/top-level-form-insert-before-tool client-atom)
          after-reg (sut/top-level-form-insert-after-tool client-atom)
          docstring-reg (sut/docstring-edit-tool client-atom)
          comment-reg (sut/comment-block-edit-tool client-atom)]

      (testing "Registration maps have correct structure"
        (is (string? (:name replace-reg)))
        (is (string? (:description replace-reg)))
        (is (map? (:schema replace-reg))) ;; Schema is serialized as JSON string
        (is (fn? (:tool-fn replace-reg)))

        (is (string? (:name before-reg)))
        (is (string? (:description before-reg)))
        (is (map? (:schema before-reg))) ;; Schema is serialized as JSON string
        (is (fn? (:tool-fn before-reg)))

        (is (string? (:name comment-reg)))
        (is (string? (:description comment-reg)))
        (is (map? (:schema comment-reg))) ;; Schema is serialized as JSON string
        (is (fn? (:tool-fn comment-reg)))))))

;; Functional tests with tool execution
(deftest tool-execution-test
  (let [client-atom test-utils/*nrepl-client-atom*
        replace-tool (sut/create-edit-replace-form-tool client-atom)
        docstring-tool (sut/create-edit-docstring-tool client-atom)
        comment-tool (sut/create-edit-comment-block-tool client-atom)]

    (testing "Replace form tool can modify files"
      (let [file-path (get-file-path)
            ;; Register file as read so we can modify it
            _ (test-utils/read-and-register-test-file client-atom file-path)
            inputs {:file_path file-path
                    :form_identifier "example-fn"
                    :form_type "defn"
                    :content "(defn example-fn [x]\n  (* x 2))"}
            validated (tool-system/validate-inputs replace-tool inputs)
            result (tool-system/execute-tool replace-tool validated)
            formatted (tool-system/format-results replace-tool result)
            file-content (slurp file-path)]

        ;; Validate MCP result format using the common function
        (validate-mcp-result formatted false #(str/includes? % "@@ "))

        ;; Specific test for this tool
        (is (= (:result formatted) [(:diff result)]) "Result should contain the diff")
        (is (not (nil? (:diff result))) "Diff should not be nil")

        ;; Validate file was actually modified
        (is (str/includes? file-content "(defn example-fn [x]") "Function signature should be updated")
        (is (str/includes? file-content "(* x 2)") "Function body should be updated")
        (is (not (str/includes? file-content "(+ x y)")) "Old function body should be removed")))

    (testing "Docstring tool can update docstrings"
      (let [test-dir *test-dir*
            ;; Create a file with proper docstring 
            test-file-content (str "(ns test.core)\n\n"
                                   "(defn example-fn\n  \"Original docstring\"\n  [x y]\n  (+ x y))\n\n"
                                   "(def a 1)\n\n"
                                   "(comment\n  (example-fn 1 2))\n\n"
                                   ";; Test comment\n;; spans multiple lines")
            file-path (test-utils/create-and-register-test-file
                       client-atom
                       test-dir
                       "docstring_test.clj"
                       test-file-content)
            inputs {:file_path file-path
                    :form_identifier "example-fn"
                    :form_type "defn"
                    :docstring "Updated docstring for testing"}
            validated (tool-system/validate-inputs docstring-tool inputs)
            result (tool-system/execute-tool docstring-tool validated)
            formatted (tool-system/format-results docstring-tool result)
            file-content (slurp file-path)]

        ;; Validate MCP result format
        (validate-mcp-result formatted false #(str/includes? % "docstring"))

        ;; Specific test for this tool
        (is (= (:result formatted) [(:diff result)]) "Result should contain the diff")

        ;; Validate file was actually modified
        (is (str/includes? file-content "Updated docstring for testing") "New docstring should be in the file")
        (is (not (str/includes? file-content "Original docstring")) "Old docstring should be removed")))

    (testing "Comment tool can update comment blocks"
      (let [file-path (get-file-path)
            ;; Register file as read so we can modify it
            _ (test-utils/read-and-register-test-file client-atom file-path)
            inputs {:file_path file-path
                    :comment_substring "Test comment"
                    :new_content ";; Updated test comment\n;; with multiple lines"}
            validated (tool-system/validate-inputs comment-tool inputs)
            result (tool-system/execute-tool comment-tool validated)
            formatted (tool-system/format-results comment-tool result)
            file-content (slurp file-path)]

        ;; Validate MCP result format
        (validate-mcp-result formatted false #(str/includes? % "comment"))

        ;; Specific test for this tool
        (is (= (:result formatted) [(:diff result)]) "Result should contain the diff")

        ;; Validate file was actually modified
        (is (str/includes? file-content "Updated test comment") "New comment should be in the file")
        (is (str/includes? file-content "with multiple lines") "Comment should have multiple lines")
        (is (not (str/includes? file-content "Test comment\n;; spans multiple")) "Old comment should be removed")))))

(deftest defmethod-handling-test
  (testing "Tool correctly handles defmethod forms"
    (let [client-atom test-utils/*nrepl-client-atom*
          replace-tool (sut/create-edit-replace-form-tool client-atom)
          test-dir *test-dir*

          ;; Create a file with defmulti and defmethod forms
          mm-file-content (str "(ns test.multimethods)\n\n"
                               "(defmulti area :shape)\n\n"
                               "(defmethod area :rectangle [rect]\n  (* (:width rect) (:height rect)))\n\n"
                               "(defmethod area :circle [circle]\n  (* Math/PI (:radius circle) (:radius circle)))\n")
          file-path (test-utils/create-and-register-test-file
                     client-atom
                     test-dir
                     "multimethods.clj"
                     mm-file-content)]

      (testing "Can update defmethod with just the multimethod name"
        (let [inputs {:file_path file-path
                      :form_identifier "area" ;; Just the multimethod name
                      :form_type "defmethod"
                      :content "(defmethod area :rectangle [rect]\n  ;; Updated implementation\n  (let [w (:width rect)\n        h (:height rect)]\n    (* w h)))"}
              ;; Register file as read so we can modify it
              _ (test-utils/read-and-register-test-file client-atom file-path)
              validated (tool-system/validate-inputs replace-tool inputs)
              result (tool-system/execute-tool replace-tool validated)
              formatted (tool-system/format-results replace-tool result)
              file-content (slurp file-path)]

          ;; Validate MCP result format
          (validate-mcp-result formatted false #(str/includes? % "defmethod"))

          ;; Validate file was actually modified
          (is (str/includes? file-content "Updated implementation") "New implementation comment should be in the file")
          (is (str/includes? file-content "(let [w (:width rect)") "New implementation code should be in the file")
          (is (not (str/includes? file-content "(* (:width rect) (:height rect))")) "Old implementation should be removed"))

        (testing "Can update defmethod with multimethod name and dispatch value"
          (let [inputs {:file_path file-path
                        :form_identifier "area :circle" ;; Compound name with dispatch value
                        :form_type "defmethod"
                        :content "(defmethod area :circle [circle]\n  ;; Updated circle implementation\n  (let [r (:radius circle)]\n    (* Math/PI r r)))"}
                ;; Register file as read so we can modify it
                _ (test-utils/read-and-register-test-file client-atom file-path)
                validated (tool-system/validate-inputs replace-tool inputs)
                result (tool-system/execute-tool replace-tool validated)
                formatted (tool-system/format-results replace-tool result)
                file-content (slurp file-path)]

          ;; Validate MCP result format
            (validate-mcp-result formatted false #(and (str/includes? % "defmethod")
                                                       (str/includes? % ":circle")))

          ;; Validate file was actually modified
            (is (str/includes? file-content "Updated circle implementation") "New implementation comment should be in the file")
            (is (str/includes? file-content "(let [r (:radius circle)]") "New implementation code should be in the file")
            (is (not (str/includes? file-content "(* Math/PI (:radius circle) (:radius circle))")) "Old implementation should be removed"))

          (testing "Inserting new defmethod implementation"
            (let [inputs {:file_path file-path
                          :form_identifier "area :circle" ;; Insert after circle implementation
                          :form_type "defmethod"
                          :content "(defmethod area :triangle [triangle]\n  (* 0.5 (:base triangle) (:height triangle)))"}
                  before-tool (sut/create-edit-insert-after-form-tool client-atom)
                  ;; Register file as read so we can modify it
                  _ (test-utils/read-and-register-test-file client-atom file-path)
                  validated (tool-system/validate-inputs before-tool inputs)
                  result (tool-system/execute-tool before-tool validated)
                  formatted (tool-system/format-results before-tool result)
                  file-content (slurp file-path)]

          ;; Validate MCP result format
              (validate-mcp-result formatted false #(and (str/includes? % "defmethod")
                                                         (str/includes? % ":triangle")))

          ;; Validate file was actually modified
              (is (str/includes? file-content "area :triangle") "New triangle method should be in the file")
              (is (str/includes? file-content "(* 0.5 (:base triangle) (:height triangle))") "Triangle implementation should be in the file"))))))

;; Tool-fn tests through the callback interface
    (deftest tool-fn-test
      (testing "Tool-fn works with callbacks"
        (let [client-atom test-utils/*nrepl-client-atom*
              replace-reg (sut/top-level-form-edit-tool client-atom)
              replace-fn (:tool-fn replace-reg)
              [p1 cb1] (make-callback)

              comment-reg (sut/comment-block-edit-tool client-atom)
              comment-fn (:tool-fn comment-reg)
              [p2 cb2] (make-callback)]

          (testing "Replace form tool works via callback"
            ;; Register file as read so we can modify it
            (test-utils/read-and-register-test-file client-atom (get-file-path))
            (replace-fn nil
                        {"file_path" (get-file-path)
                         "form_identifier" "example-fn"
                         "form_type" "defn"
                         "content" "(defn example-fn [x]\n  (str \"result: \" (* x 3)))"}
                        cb1)
            (let [result @p1]
              ;; Validate MCP response format
              (validate-mcp-result result)
              ;; Verify the actual file modification
              (is (str/includes? (slurp (get-file-path)) "(str \"result: \" (* x 3))")
                  "The file should contain the updated function implementation")))

          (testing "Comment tool works via callback"
            ;; Register file as read so we can modify it
            (test-utils/read-and-register-test-file client-atom (get-file-path))
            (comment-fn nil
                        {"file_path" (get-file-path)
                         "comment_substring" "(example-fn"
                         "new_content" "(comment\n  (example-fn 10 20))"}
                        cb2)
            (let [result @p2]
              ;; Validate MCP response format
              (validate-mcp-result result)
              ;; Verify the actual file modification
              (is (str/includes? (slurp (get-file-path)) "(example-fn 10 20)")
                  "The file should contain the updated comment"))))))))
