(ns clojure-mcp.tools.file-write.tool-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.tools.file-write.tool :as file-write-tool]
   [clojure-mcp.tools.file-write.core :as file-write-core]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure-mcp.config :as config] ; Added config require
   [clojure-mcp.tool-system :as tool-system]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Setup test fixtures
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-client-atom* nil)

(defn create-test-fixture [f]
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir") "clojure-mcp-tool-test")]
    ;; Create test directory
    (.mkdirs test-dir)

    ;; Initialize client atom using config/set-config!
    (let [client-atom (atom {})]
      (config/set-config! client-atom :nrepl-user-dir (.getCanonicalPath test-dir))
      (config/set-config! client-atom :allowed-directories [(.getCanonicalPath test-dir)])
      (config/set-config! client-atom ::file-timestamps/file-timestamps {})

      ;; Bind dynamic vars for test
      (binding [*test-dir* test-dir
                *test-client-atom* client-atom]
        (try
          (f)
          (finally
            ;; Clean up
            (.delete test-dir)))))))

(use-fixtures :each create-test-fixture)

(deftest tool-metadata-test
  (testing "Tool metadata multimethods"
    (let [tool-config (file-write-tool/create-file-write-tool (atom {}))]
      ;; Test tool-name
      (is (= "file_write" (tool-system/tool-name tool-config)))

      ;; Test tool-description
      (let [description (tool-system/tool-description tool-config)]
        (is (string? description))
        (is (str/includes? description "local filesystem"))
        (is (str/includes? description "Content will be linted")))

      ;; Test tool-schema
      (let [schema (tool-system/tool-schema tool-config)]
        (is (map? schema))
        (is (= :object (:type schema)))
        (is (contains? (:properties schema) :file_path))
        (is (contains? (:properties schema) :content))
        (is (some #(= % :file_path) (:required schema)))
        (is (some #(= % :content) (:required schema)))))))

(deftest validate-inputs-test
  (testing "Validate inputs with missing parameters"
    (let [tool-config (file-write-tool/create-file-write-tool (atom {}))
          validate-fn #(tool-system/validate-inputs tool-config %)]

      ;; Test missing file_path
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing required parameter: file_path"
                            (validate-fn {:content "test content"})))

      ;; Test missing content
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing required parameter: content"
                            (validate-fn {:file_path "/tmp/test.txt"}))))))

(deftest format-results-test
  (testing "Format success results"
    (let [tool-config (file-write-tool/create-file-write-tool (atom {}))]

      ;; Test formatting success without diff
      (let [result {:error false
                    :type "create"
                    :file-path "/test/path/file.clj"
                    :diff ""}
            formatted (tool-system/format-results tool-config result)]
        (is (map? formatted))
        (is (vector? (:result formatted)))
        (is (= 1 (count (:result formatted))))
        (is (str/includes? (first (:result formatted)) "Clojure file created"))
        (is (not (:error formatted))))

      ;; Test formatting success with diff
      (let [result {:error false
                    :type "update"
                    :file-path "/test/path/file.txt"
                    :diff "- old content\n+ new content"}
            formatted (tool-system/format-results tool-config result)]
        (is (map? formatted))
        (is (vector? (:result formatted)))
        (is (= 1 (count (:result formatted))))
        (is (str/includes? (first (:result formatted)) "Text file updated"))
        (is (str/includes? (first (:result formatted)) "Changes:"))
        (is (str/includes? (first (:result formatted)) "- old content"))
        (is (not (:error formatted))))))

  (testing "Format error results"
    (let [tool-config (file-write-tool/create-file-write-tool (atom {}))]

      ;; Test formatting error
      (let [result {:error true
                    :message "Error writing file: Permission denied"}
            formatted (tool-system/format-results tool-config result)]
        (is (map? formatted))
        (is (vector? (:result formatted)))
        (is (= 1 (count (:result formatted))))
        (is (str/includes? (first (:result formatted)) "Error writing file"))
        (is (:error formatted))))))

(deftest registration-map-test
  (testing "Registration map"
    (let [reg-map (file-write-tool/file-write-tool (atom {}))]
      (is (map? reg-map))
      (is (= "file_write" (:name reg-map)))
      (is (string? (:description reg-map)))
      (is (map? (:schema reg-map)))
      (is (fn? (:tool-fn reg-map))))))

(deftest tool-fn-integration-test
  (testing "Tool-fn callback with valid Clojure code"
    (let [tool-config (file-write-tool/create-file-write-tool *test-client-atom*)
          reg-map (tool-system/registration-map tool-config)
          tool-fn (:tool-fn reg-map)
          file-path (str (.getCanonicalPath *test-dir*) "/valid-callback.clj")
          valid-content "(ns test.callback)\n\n(defn callback-fn [x]\n  (+ x 100))"

          ;; For testing purposes, if the file might exist, we should "mark" it as read first
          _ (file-timestamps/update-file-timestamp-to-current-mtime! *test-client-atom* file-path)

          ;; Create promise for callback result
          p (promise)
          callback (fn [result error] (deliver p {:result result :error error}))]

      ;; Execute tool function with callback
      (tool-fn nil {:file_path file-path :content valid-content} callback)

      ;; Wait for result (with timeout)
      (let [result (deref p 5000 {:error true :result ["Timeout waiting for callback"]})]
        ;; Validate result
        (is (not (:error result)))
        (is (vector? (:result result)))
        (is (or (str/includes? (first (:result result)) "Clojure file created")
                (str/includes? (first (:result result)) "Clojure file updated")))

        ;; Verify file exists and has correct content
        (is (.exists (io/file file-path)))
        (is (str/includes? (slurp file-path) "callback-fn"))))))

(deftest integration-test
  (testing "End-to-end valid Clojure file write"
    (let [tool-config (file-write-tool/create-file-write-tool *test-client-atom*)
          file-path (str (.getCanonicalPath *test-dir*) "/valid.clj")
          valid-content "(ns test.valid)\n\n(defn valid-function [x]\n  (+ x 5))"

          ;; For testing purposes, if the file might exist, we should "mark" it as read first
          _ (file-timestamps/update-file-timestamp-to-current-mtime! *test-client-atom* file-path)

          ;; Execute the full pipeline from validation through execution to formatting results
          validated-inputs (tool-system/validate-inputs tool-config
                                                        {:file_path file-path
                                                         :content valid-content})
          execution-result (tool-system/execute-tool tool-config validated-inputs)
          formatted-result (tool-system/format-results tool-config execution-result)]

      ;; Test that tool-system validated and created proper structured inputs
      (is (string? (:file-path validated-inputs)))
      (is (= valid-content (:content validated-inputs)))

      ;; Test successful execution
      (is (not (:error execution-result)))
      (is (contains? #{"create" "update"} (:type execution-result)))
      (is (= file-path (:file-path execution-result)))
      (is (string? (:diff execution-result)))

      ;; Test formatted results 
      (is (map? formatted-result))
      (is (not (:error formatted-result)))
      (is (vector? (:result formatted-result)))
      (is (or (str/includes? (first (:result formatted-result)) "Clojure file created")
              (str/includes? (first (:result formatted-result)) "Clojure file updated")))

      ;; Verify file exists and has correct content
      (is (.exists (io/file file-path)))
      (is (= valid-content (slurp file-path)))))

  (testing "End-to-end auto-repair for Clojure file with delimiter errors"
    (let [tool-config (file-write-tool/create-file-write-tool *test-client-atom*)
          file-path (str (.getCanonicalPath *test-dir*) "/repairable.clj")
          content-with-delimiter-error "(ns test.repairable)\n\n(defn missing-paren [x]\n  (+ x 5)"

          ;; For testing purposes, if the file might exist, we should "mark" it as read first
          _ (file-timestamps/update-file-timestamp-to-current-mtime! *test-client-atom* file-path)

          ;; Execute the full pipeline from validation through execution to formatting results
          validated-inputs (tool-system/validate-inputs tool-config
                                                        {:file_path file-path
                                                         :content content-with-delimiter-error})

          ;; The repair happens during execute-tool, not in validate-inputs
          execution-result (tool-system/execute-tool tool-config validated-inputs)
          formatted-result (tool-system/format-results tool-config execution-result)]

      ;; Test that tool-system validated the inputs - no repair happens here yet
      (is (string? (:file-path validated-inputs)))
      (is (= content-with-delimiter-error (:content validated-inputs)))

      ;; Test successful execution - the repair happens here via write-clojure-file
      (is (not (:error execution-result)))
      (is (contains? #{"create" "update"} (:type execution-result)))
      (is (= file-path (:file-path execution-result)))
      (is (string? (:diff execution-result)))

      ;; Test formatted results 
      (is (map? formatted-result))
      (is (not (:error formatted-result)))
      (is (vector? (:result formatted-result)))
      (is (or (str/includes? (first (:result formatted-result)) "Clojure file created")
              (str/includes? (first (:result formatted-result)) "Clojure file updated")))

      ;; Verify file exists and has repaired content
      (is (.exists (io/file file-path)))
      (let [final-content (slurp file-path)]
        (is (not= content-with-delimiter-error final-content))
        ;; The repaired content should have an extra closing parenthesis
        (is (re-find #"\(defn missing-paren \[x\]\s+\(\+ x 5\)\)"
                     final-content))))))

#_(re-find #"\(defn missing-paren \[x\]\s+\(\+ x 5\)\)" "(ns test.repairable)\n\n(defn missing-paren [x]\n  (+ x 5))")

(deftest file-timestamp-check-test
  (testing "File modified since last read check"
    (let [tool-config (file-write-tool/create-file-write-tool *test-client-atom*)
          file-path (str (.getCanonicalPath *test-dir*) "/modified-test.clj")
          initial-content "(ns test.modified)\n\n(defn initial-fn [] :initial)"]

      ;; First create the file
      (spit file-path initial-content)

      ;; Record that we read the file (update timestamp in client)
      (file-timestamps/update-file-timestamp! *test-client-atom* file-path)

      ;; Modify the file outside of our system
      (Thread/sleep 100) ;; Ensure modification time is different
      (spit file-path "(ns test.modified)\n\n(defn modified-fn [] :modified)")

      ;; Now try to write to the file - should fail with "modified since last read" error
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"File has been modified since last read"
                            (tool-system/validate-inputs
                             tool-config
                             {:file_path file-path
                              :content "(ns test.modified)\n\n(defn new-fn [] :new)"})))

      ;; After reading the file again, the write should succeed
      (file-timestamps/update-file-timestamp-to-current-mtime! *test-client-atom* file-path)

      (let [validated-inputs (tool-system/validate-inputs
                              tool-config
                              {:file_path file-path
                               :content "(ns test.modified)\n\n(defn new-fn [] :new)"})
            execution-result (tool-system/execute-tool tool-config validated-inputs)]

        ;; Verify the write was successful
        (is (not (:error execution-result)))
        (is (= "update" (:type execution-result)))
        (is (= file-path (:file-path execution-result)))

        ;; Check the file content was updated
        (is (str/includes? (slurp file-path) "new-fn")))

      ;; Clean up
      (io/delete-file file-path true))))
