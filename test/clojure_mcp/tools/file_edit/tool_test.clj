(ns clojure-mcp.tools.file-edit.tool-test
  (:require [clojure.test :refer :all]
            [clojure-mcp.tools.file-edit.tool :as tool]
            [clojure-mcp.tool-system :as tool-system]
            [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
            [clojure-mcp.config :as config] ; Added config require
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Create a real nREPL client atom with paths set to the project directory
(def project-dir (System/getProperty "user.dir"))
(def tmp-dir (str project-dir "/tmp/file-edit-test"))
(def test-client-atom (atom {})) ; Initialize with an empty map

;; Helper to set up the atom for tests
(defn initialize-test-client-atom! []
  (reset! test-client-atom {}) ; Clear previous state
  (config/set-config! test-client-atom :nrepl-user-dir project-dir)
  (config/set-config! test-client-atom :allowed-directories [project-dir])
  ;; TODO filestamps should have its own accessors
  ;; when it matures
  (config/set-config! test-client-atom ::file-timestamps/file-timestamps {}))

;; Test fixtures
(defn setup-tmp-dir [f]
  (initialize-test-client-atom!) ; Initialize atom at the start of each test
  ;; Clean up any previous directories
  (let [dir (io/file tmp-dir)]
    (when (.exists dir)
      (doseq [file (file-seq (io/file tmp-dir))]
        (when (.isFile file)
          (.delete file))))
    (when (.exists dir)
      (doseq [file (reverse (file-seq (io/file tmp-dir)))]
        (.delete file))))

  ;; Create fresh tmp directory and test files
  (let [dir (io/file tmp-dir)]
    (.mkdirs dir)
    ;; Create a test file
    (spit (io/file tmp-dir "test-file.txt") "This is line one\nThis is line two\nThis is line three\n")
    ;; Update timestamp to simulate file being read
    (file-timestamps/update-file-timestamp-to-current-mtime! test-client-atom
                                                             (str tmp-dir "/test-file.txt")))

  ;; Run the test
  (f)

  ;; Clean up after test
  (doseq [file (file-seq (io/file tmp-dir))]
    (when (.isFile file)
      (.delete file)))
  (doseq [file (reverse (file-seq (io/file tmp-dir)))]
    (.delete file)))

(use-fixtures :each setup-tmp-dir)

;; Tool configuration tests
(deftest tool-config-test
  (testing "Tool name is correct"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)]
      (is (= "file_edit" (tool-system/tool-name tool-config)))))

  (testing "Tool schema has required properties"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          schema (tool-system/tool-schema tool-config)]
      (is (= :object (:type schema)))
      (is (contains? (:properties schema) :file_path))
      (is (contains? (:properties schema) :old_string))
      (is (contains? (:properties schema) :new_string))
      (is (= [:file_path :old_string :new_string] (:required schema))))))

;; Integration tests with actual file operations
(deftest file-edit-integration-test
  (testing "Editing a Clojure file with valid syntax"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          file-path (str tmp-dir "/valid.clj")
          ;; Create a valid Clojure file
          _ (spit file-path "(ns test.valid)\n\n(defn test-fn [x]\n  (* x 2))")
          ;; Update timestamp to simulate file being read
          _ (file-timestamps/update-file-timestamp-to-current-mtime! test-client-atom file-path)
          old-string "(* x 2)"
          new-string "(+ x 10)"

          ;; Execute the validation and editing steps
          inputs {:file_path file-path
                  :old_string old-string
                  :new_string new-string}
          validated-inputs (tool-system/validate-inputs tool-config inputs)
          result (tool-system/execute-tool tool-config validated-inputs)
          formatted-result (tool-system/format-results tool-config result)]

      ;; Verify the tool results - should succeed because syntax is valid
      (is (not (:error formatted-result)) "Operation should succeed with valid Clojure syntax")
      (is (= "update" (:type formatted-result)) "Should have update type")

      ;; Verify the actual file content
      (let [content (slurp file-path)]
        (is (str/includes? content "(+ x 10)") "File should contain the new valid code"))))

  (testing "Editing a Clojure file with syntax errors should not fail"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          file-path (str tmp-dir "/test.clj")
          ;; Create a valid Clojure file
          _ (spit file-path "(ns test.syntax)\n\n(defn valid-fn [x]\n  (+ x 1))")
          ;; Update timestamp to simulate file being read
          _ (file-timestamps/update-file-timestamp-to-current-mtime! test-client-atom file-path)
          original-content (slurp file-path)
          old-string "(+ x 1)"
          ;; Missing closing parenthesis - syntax error
          new-string "(let [y (inc x]\n    (println y)"

          ;; Execute the validation and editing steps
          inputs {:file_path file-path
                  :old_string old-string
                  :new_string new-string}
          validated-inputs (tool-system/validate-inputs tool-config inputs)
          result (tool-system/execute-tool tool-config validated-inputs)
          formatted-result (tool-system/format-results tool-config result)]

      ;; Verify the tool results - should report error due to syntax issue
      (is (not (:error formatted-result))
          "Operation should not fail with invalid Clojure syntax")
      (is (not (str/includes? (first (:result formatted-result)) "Syntax errors"))
          "Error should not mention syntax issues")

      ;; Verify file was  modified
      (is (not= original-content (slurp file-path)) "File should be modified when syntax errors detected")))

  (testing "Creating a new Clojure file with empty old_string should be rejected"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          file-path (str tmp-dir "/invalid-new.clj")
          ;; Content with valid syntax
          new-content "(ns test.new)\n\n(defn test-fn [x]\n  (+ x 1))"

          ;; Try to execute with empty old_string - should be rejected
          inputs {:file_path file-path
                  :old_string ""
                  :new_string new-content}]

      ;; Validation should catch the empty old_string and throw an exception
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Empty old_string is not supported"
                            (tool-system/validate-inputs tool-config inputs))
          "Should reject empty old_string during validation")))

  (testing "Editing an existing file"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          file-path (str tmp-dir "/test-file.txt")
          old-string "This is line two"
          new-string "This is line two - EDITED"

          ;; Execute the validation and editing steps
          inputs {:file_path file-path
                  :old_string old-string
                  :new_string new-string}
          validated-inputs (tool-system/validate-inputs tool-config inputs)
          result (tool-system/execute-tool tool-config validated-inputs)
          formatted-result (tool-system/format-results tool-config result)]

      ;; Verify the tool results
      (is (not (:error formatted-result)) "Operation should succeed")
      (is (= "update" (:type formatted-result)) "Should have update type")
      (is (string? (:diff result))
          "Should contain a diff")
      (is (= (:result formatted-result) [(:diff result)]) "Should contain a diff")

      ;; Verify the actual file content
      (let [content (slurp file-path)]
        (is (str/includes? content new-string) "File should contain the new string"))))

  (testing "Creating a new file should be rejected, use file_write instead"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          file-path (str tmp-dir "/new-file.txt")
          new-content "This is a brand new file\nWith multiple lines\nCreated by file_edit tool"

          ;; Try to create a new file - should be rejected
          inputs {:file_path file-path
                  :old_string ""
                  :new_string new-content}]

      ;; Validation should reject empty old_string
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Empty old_string is not supported"
                            (tool-system/validate-inputs tool-config inputs))
          "Should reject empty old_string during validation")))

  (testing "Creating a file with nested directories should be rejected"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          nested-dir (str tmp-dir "/nested/dirs")
          file-path (str nested-dir "/deep-file.txt")
          new-content "File in nested directories"

          ;; Try to create a file in nested directories - should be rejected
          inputs {:file_path file-path
                  :old_string ""
                  :new_string new-content}]

      ;; Validation should reject empty old_string
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Empty old_string is not supported"
                            (tool-system/validate-inputs tool-config inputs))
          "Should reject empty old_string during validation")))

  (testing "Error case: non-unique match"
    (let [;; Create a file with duplicate lines
          dupe-file-path (str tmp-dir "/duplicate.txt")
          _ (spit dupe-file-path "Duplicate line\nDuplicate line\nDuplicate line\n")
          ;; Update timestamp to simulate file being read
          _ (file-timestamps/update-file-timestamp-to-current-mtime! test-client-atom dupe-file-path)

          tool-config (tool/create-file-edit-tool test-client-atom)
          inputs {:file_path dupe-file-path
                  :old_string "Duplicate line"
                  :new_string "Modified line"}]

      ;; Validation should pass since we don't check uniqueness until execution
      (let [validated-inputs (tool-system/validate-inputs tool-config inputs)
            result (tool-system/execute-tool tool-config validated-inputs)
            formatted-result (tool-system/format-results tool-config result)]

        ;; Verify the error is reported
        (is (:error formatted-result) "Should report an error for non-unique match")
        (is (str/includes? (first (:result formatted-result)) "matches")
            "Error message should mention multiple matches"))))

  (testing "Error case: file doesn't exist"
    (let [tool-config (tool/create-file-edit-tool test-client-atom)
          non-existent-file (str tmp-dir "/does-not-exist.txt")
          inputs {:file_path non-existent-file
                  :old_string "Some content"
                  :new_string "New content"}

          ;; Validate inputs should pass since this is a path validation only
          validated-inputs (tool-system/validate-inputs tool-config inputs)
          result (tool-system/execute-tool tool-config validated-inputs)
          formatted-result (tool-system/format-results tool-config result)]

      ;; Verify the error is reported
      (is (:error formatted-result) "Should report an error for non-existent file")
      (is (str/includes? (first (:result formatted-result)) "not found")
          "Error message should mention file doesn't exist"))))

;; Registration function test
(deftest registration-function-test
  (testing "file-edit-tool function returns a valid registration map"
    (let [reg-map (tool/file-edit-tool test-client-atom)]
      (is (= "file_edit" (:name reg-map)))
      (is (string? (:description reg-map)))
      (is (map? (:schema reg-map)))
      (is (fn? (:tool-fn reg-map))))))

;; Tool function execution test (simulating how Claude would call it)
(deftest tool-fn-execution-test
  (testing "Tool function handles arguments and callback properly"
    (let [tool-reg-map (tool/file-edit-tool test-client-atom)
          tool-fn (:tool-fn tool-reg-map)
          file-path (str tmp-dir "/tool-fn-test.txt")
          test-content "Test content for tool-fn\nSecond line\nThird line"
          _ (spit file-path test-content)
          ;; Update timestamp to simulate file being read
          _ (file-timestamps/update-file-timestamp-to-current-mtime! test-client-atom file-path)

          ;; Track callback invocation
          callback-result (atom nil)
          callback-fn (fn [result error] (reset! callback-result {:result result :error error}))]

      ;; Call the tool function as Claude would
      (tool-fn nil
               {"file_path" file-path
                "old_string" "Second line"
                "new_string" "Second line - MODIFIED"}
               callback-fn)

      ;; Wait a bit for async execution to complete
      (Thread/sleep 100)

      ;; Check callback was called with correct result
      (is (not (nil? @callback-result)) "Callback should have been called")
      (is (not (:error @callback-result)) "Should not report an error")

      ;; Verify the file was actually modified
      (let [updated-content (slurp file-path)]
        (is (str/includes? updated-content "Second line - MODIFIED") "File should be modified")))))
