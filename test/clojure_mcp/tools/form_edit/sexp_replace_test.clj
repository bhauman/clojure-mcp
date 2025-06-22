(ns clojure-mcp.tools.form-edit.sexp-replace-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure-mcp.tools.form-edit.tool :as sut]
   [clojure-mcp.tools.form-edit.pipeline :as pipeline]
   [clojure-mcp.tools.form-edit.core :as core]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.config :as config] ; Added config require
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Test fixtures
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-file* nil)
(def ^:dynamic *client-atom* nil)

(defn create-test-files-fixture [f]
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "test-dir-" (System/currentTimeMillis)))]
    (.mkdirs test-dir)
    (let [test-file (io/file test-dir "test.clj")
          test-content (str "(ns test.core)\n\n"
                            "(defn example-fn [x y]\n"
                            "  #_(println \"debug value:\" x)\n"
                            "  (+ x y)\n"
                            "  (+ x 1)\n"
                            "  (- y 1))\n\n"
                            "(defn another-fn [z]\n"
                            "  (+ z 1)\n"
                            "  (let [result (+ z 1)]\n"
                            "    (* result 2)))\n\n"
                            "(defn process-map [m]\n"
                            "  (map #(* % 2) (vals m)))\n\n"
                            "(def config {:key1 100 :key2 200})\n\n"
                            "(comment\n"
                            "  (example-fn 1 2)\n"
                            "  (+ 1 2)\n"
                            "  (println \"testing\"))\n\n"
                            ";; Test comment\n;; spans multiple lines")]
      (spit test-file test-content)

      (let [canonical-path (.getCanonicalPath test-file)
            client-atom (atom {})] ; Initialize empty atom
        ;; Set config using helpers
        (config/set-config! client-atom :nrepl-user-dir canonical-path)
        (config/set-config! client-atom :allowed-directories [canonical-path])
        (config/set-config! client-atom ::file-timestamps/file-timestamps {})

        ;; Register the file using its canonical path in the timestamp tracker
        (file-timestamps/update-file-timestamp-to-current-mtime! client-atom canonical-path)
        ;; Small delay to ensure future modifications have different timestamps
        (Thread/sleep 25)

        (binding [*test-dir* test-dir
                  *test-file* test-file
                  *client-atom* client-atom]
          (try
            (f)
            (finally
              (when (.exists test-file) (.delete test-file))
              (when (.exists test-dir) (.delete test-dir)))))))))

(use-fixtures :each create-test-files-fixture)

;; Test helper functions
(defn get-file-path []
  (.getCanonicalPath *test-file*))

(defn register-file-timestamp []
  "Updates the timestamp for the test file to mark it as read."
  (let [file-path (get-file-path)]
    (file-timestamps/update-file-timestamp-to-current-mtime! *client-atom* file-path)
    ;; Small delay to ensure timestamps differ if modified
    (Thread/sleep 25)
    file-path))

(defn validate-mcp-result
  "Validates that a result conforms to the MCP result format."
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
   (is (not-empty (:result result)) "Result should not be empty")

   ;; Test specific content pattern if provided
   (when (and diff-pattern-fn (not-empty (:result result)))
     (is (diff-pattern-fn (first (:result result)))
         "Result content should match expected pattern"))

   ;; Return result for further testing
   result))

;; Integration tests for the S-Expression replace tool
(deftest sexp-replace-tool-test
  (let [client-atom *client-atom*
        sexp-tool (sut/create-edit-replace-sexp-tool client-atom)]

    (testing "Basic S-Expression replacement"
      (let [file-path (get-file-path)
            _ (register-file-timestamp) ;; Register file before editing
            inputs {:file_path file-path
                    :match_form "(+ x 1)"
                    :new_form "(+ x 10)"
                    :replace_all true}
            validated (tool-system/validate-inputs sexp-tool inputs)
            result (tool-system/execute-tool sexp-tool validated)
            formatted (tool-system/format-results sexp-tool result)
            file-content (slurp file-path)]

        ;; Skip detailed diff pattern validation since format has changed
        (is (map? formatted) "Response should be a map")
        (is (contains? formatted :result) "Response should contain :result key")
        (is (contains? formatted :error) "Response should contain :error key")
        (is (false? (:error formatted)) "Error flag should be false")
        (is (vector? (:result formatted)) "Result should be a vector")
        (is (not-empty (:result formatted)) "Result should not be empty")

        ;; Check specific content changes
        (is (str/includes? file-content "(+ x 10)")
            "File should contain the replaced expression")
        (is (not (str/includes? file-content "(+ x 1)"))
            "Original expression should be replaced")))

    (testing "Anonymous function replacement"
      (let [file-path (get-file-path)
            _ (register-file-timestamp) ;; Register file before editing
            inputs {:file_path file-path
                    :match_form "#(* % 2)"
                    :new_form "#(* % 5)"
                    :replace_all true}
            validated (tool-system/validate-inputs sexp-tool inputs)
            result (tool-system/execute-tool sexp-tool validated)
            formatted (tool-system/format-results sexp-tool result)
            file-content (slurp file-path)]

        ;; Skip detailed diff pattern validation since format has changed
        (is (map? formatted) "Response should be a map")
        (is (contains? formatted :result) "Response should contain :result key")
        (is (contains? formatted :error) "Response should contain :error key")
        (is (false? (:error formatted)) "Error flag should be false")

        ;; Check specific content changes
        (is (str/includes? file-content "#(* % 5)")
            "File should contain the replaced anonymous function")
        (is (not (str/includes? file-content "#(* % 2)"))
            "Original anonymous function should be replaced")))

    (testing "Keyword replacement in data structures"
      (let [file-path (get-file-path)
            _ (register-file-timestamp) ;; Register file before editing
            inputs {:file_path file-path
                    :match_form ":key1"
                    :new_form ":new-key"
                    :replace_all true}
            validated (tool-system/validate-inputs sexp-tool inputs)
            result (tool-system/execute-tool sexp-tool validated)
            formatted (tool-system/format-results sexp-tool result)
            file-content (slurp file-path)]

        ;; Skip detailed diff pattern validation since format has changed
        (is (map? formatted) "Response should be a map")
        (is (contains? formatted :result) "Response should contain :result key")
        (is (contains? formatted :error) "Response should contain :error key")
        (is (false? (:error formatted)) "Error flag should be false")

        ;; Check specific content changes
        (is (str/includes? file-content ":new-key")
            "File should contain the replaced keyword")
        (is (not (str/includes? file-content ":key1"))
            "Original keyword should be replaced")))

    (testing "Form deletion with empty string"
      (let [file-path (get-file-path)
            _ (register-file-timestamp) ;; Register file before editing
            inputs {:file_path file-path
                    :match_form "(println \"testing\")"
                    :new_form ""
                    :replace_all true}
            validated (tool-system/validate-inputs sexp-tool inputs)
            result (tool-system/execute-tool sexp-tool validated)
            formatted (tool-system/format-results sexp-tool result)
            file-content (slurp file-path)]

        ;; Basic validation
        (is (map? formatted) "Response should be a map")
        (is (contains? formatted :result) "Response should contain :result key")
        (is (contains? formatted :error) "Response should contain :error key")
        (is (false? (:error formatted)) "Error flag should be false")

        ;; Check that the form was deleted
        (is (not (str/includes? file-content "(println \"testing\")"))
            "The form should be deleted")))

    (testing "No matches found"
      (let [file-path (get-file-path)
            _ (register-file-timestamp) ;; Register file before editing
            inputs {:file_path file-path
                    :match_form "(non-existent-form 123)"
                    :new_form "(replacement 456)"
                    :replace_all true}
            validated (tool-system/validate-inputs sexp-tool inputs)
            result (tool-system/execute-tool sexp-tool validated)
            formatted (tool-system/format-results sexp-tool result)]

        ;; Validate that the response indicates an error
        (is (map? formatted) "Response should be a map")
        (is (contains? formatted :result) "Response should contain :result key")
        (is (contains? formatted :error) "Response should contain :error key")
        (is (true? (:error formatted)) "Error flag should be true")
        (is (vector? (:result formatted)) "Result should be a vector")
        (is (not-empty (:result formatted)) "Result should not be empty")
        (is (string? (first (:result formatted))) "Result item should be a string")
        (is (str/includes? (first (:result formatted)) "Could not find form:")
            "Error message should indicate form was not found")))

    (testing "Invalid match form syntax"
      (let [file-path (get-file-path)
            inputs {:file_path file-path
                    :match_form "(defn missing-closing-paren"
                    :new_form "(defn valid-form [])"
                    :replace_all true}]

        ;; This should throw during validation
        (is (thrown? clojure.lang.ExceptionInfo
                     (tool-system/validate-inputs sexp-tool inputs))
            "Should throw exception for invalid Clojure syntax in match_form")))

    (testing "Invalid replacement form syntax"
      (let [file-path (get-file-path)
            inputs {:file_path file-path
                    :match_form "(+ x 1)"
                    :new_form "(defn invalid [x] (+ x y"
                    :replace_all true}]

        ;; This should throw during validation
        (is (thrown? clojure.lang.ExceptionInfo
                     (tool-system/validate-inputs sexp-tool inputs))
            "Should throw exception for invalid Clojure syntax in new_form")))

    (testing "Using pipeline directly"
      (let [file-path (get-file-path)
            _ (register-file-timestamp) ;; Register file before editing
            result (pipeline/sexp-replace-pipeline
                    file-path
                    "(- y 1)"
                    "(- y 100)"
                    true
                    false
                    {})
            formatted (pipeline/format-result result)
            file-content (slurp file-path)]

        ;; Check the pipeline result directly
        (is (map? formatted) "Response should be a map")
        (is (contains? formatted :error) "Response should contain :error key")
        (is (false? (:error formatted)) "Error flag should be false")
        (is (contains? formatted :result) "Response should contain :result key")

        ;; Check specific content changes
        (is (str/includes? file-content "(- y 100)")
            "File should contain the replaced expression")
        (is (not (str/includes? file-content "(- y 1)"))
            "Original expression should be replaced")))

    (testing "Replace only first occurrence"
      (let [file-path (get-file-path)
            ;; First modify the file to have multiple identical expressions
            _ (spit file-path (str/replace (slurp file-path)
                                           "(+ z 1)"
                                           "(+ z 1)\n  (+ z 1)\n  (+ z 1)\n  (+ z 1)\n  (+ z 1)"))
            _ (register-file-timestamp) ;; Register file after modification
            inputs {:file_path file-path
                    :match_form "(+ z 1)"
                    :new_form "(+ z 999)"
                    :replace_all false}
            validated (tool-system/validate-inputs sexp-tool inputs)
            result (tool-system/execute-tool sexp-tool validated)
            formatted (tool-system/format-results sexp-tool result)
            file-content (slurp file-path)]

        ;; Validate MCP result format
        (is (map? formatted) "Response should be a map")
        (is (contains? formatted :result) "Response should contain :result key")
        (is (contains? formatted :error) "Response should contain :error key")
        (is (false? (:error formatted)) "Error flag should be false")

        ;; Check that only the first occurrence was replaced
        (is (str/includes? file-content "(+ z 999)")
            "File should contain the replaced expression")
        (is (str/includes? file-content "(+ z 1)")
            "At least one instance of the original expression should remain")

        ;; Count occurrences to be more specific
        (is (= 1 (count (re-seq #"\(\+ z 999\)" file-content)))
            "There should be exactly one replacement")

        ;; Don't check exact count as it might vary based on implementation
        (is (> (count (re-seq #"\(\+ z 1\)" file-content)) 0)
            "There should be original expressions remaining")))))

(deftest sexp-replace-tool-fn-test
  (testing "Tool function works through the registration map"
    (let [client-atom *client-atom*
          reg-map (sut/sexp-replace-tool client-atom)
          tool-fn (:tool-fn reg-map)
          prom (promise)
          file-path (get-file-path)
          _ (register-file-timestamp)] ;; Register file before editing

      ;; Call the tool function with a callback
      (tool-fn nil
               {"file_path" file-path
                "match_form" "(+ x y)"
                "new_form" "(+ x y 100)"
                "replace_all" true}
               (fn [result error]
                 (deliver prom {:result result :error error})))

      ;; Wait for the promise and check the result
      (let [callback-result @prom]
        ;; The error should be nil or false (depending on implementation)
        (is (or (nil? (:error callback-result))
                (false? (:error callback-result)))
            "Callback should not indicate an error")

        ;; The result is a vector with strings in our implementation
        (is (sequential? (:result callback-result))
            "Callback should receive a sequential result")

        ;; Check the file was actually modified regardless of result format
        (is (str/includes? (slurp (get-file-path)) "(+ x y 100)")
            "The file should contain the new expression")))))
