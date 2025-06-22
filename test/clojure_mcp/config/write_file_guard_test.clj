(ns clojure-mcp.config.write-file-guard-test
  "Tests for the write-file-guard configuration option"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.config :as config]
   [clojure-mcp.tools.file-write.tool :as file-write-tool]
   [clojure-mcp.tools.unified-read-file.tool :as unified-read-file-tool]
   [clojure-mcp.tools.file-edit.tool :as file-edit-tool]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure-mcp.tool-system :as tool-system]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Test fixture setup
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-client-atom* nil)

(defn create-test-fixture [f]
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "clojure-mcp-write-guard-test-" (System/currentTimeMillis)))]
    ;; Create test directory
    (.mkdirs test-dir)

    ;; Initialize client atom
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
            ;; Clean up - recursively delete test directory
            (doseq [file (reverse (file-seq test-dir))]
              (.delete file))))))))

(use-fixtures :each create-test-fixture)

;; Helper functions
(defn set-write-file-guard! [mode]
  "Set the write-file-guard configuration"
  (config/set-config! *test-client-atom* :write-file-guard mode))

(defn create-test-file! [filename content]
  "Create a test file with the given content"
  (let [file-path (str (.getCanonicalPath *test-dir*) "/" filename)]
    (spit file-path content)
    file-path))

(defn read-file-collapsed! [file-path]
  "Perform a collapsed read using unified-read-file tool"
  (let [tool-config (unified-read-file-tool/create-unified-read-file-tool *test-client-atom*)
        inputs {:path file-path :collapsed true}
        validated (tool-system/validate-inputs tool-config inputs)]
    (tool-system/execute-tool tool-config validated)))

(defn read-file-full! [file-path]
  "Perform a full read using unified-read-file tool"
  (let [tool-config (unified-read-file-tool/create-unified-read-file-tool *test-client-atom*)
        inputs {:path file-path :collapsed false}
        validated (tool-system/validate-inputs tool-config inputs)]
    (tool-system/execute-tool tool-config validated)))

(defn edit-file! [file-path old-string new-string]
  "Attempt to edit a file using file-edit tool"
  (let [tool-config (file-edit-tool/create-file-edit-tool *test-client-atom*)
        inputs {:file_path file-path :old_string old-string :new_string new-string}]
    (try
      (let [validated (tool-system/validate-inputs tool-config inputs)
            result (tool-system/execute-tool tool-config validated)]
        ;; Check if the result indicates an error
        (if (:error result)
          {:success false :error (:message result)}
          {:success true :result result}))
      (catch Exception e
        ;; Debug: print the exception message
        (println "Edit failed with exception:" (.getMessage e))
        {:success false :error (.getMessage e)}))))

(defn write-file! [file-path content]
  "Attempt to write a file using file-write tool"
  (let [tool-config (file-write-tool/create-file-write-tool *test-client-atom*)]
    (try
      (let [validated (tool-system/validate-inputs tool-config
                                                   {:file_path file-path :content content})
            result (tool-system/execute-tool tool-config validated)]
        ;; Check if the result indicates an error
        (if (:error result)
          {:success false :error (:message result)}
          {:success true :result result}))
      (catch Exception e
        {:success false :error (.getMessage e)}))))

;; Tests for :full-read mode (default)
(deftest full-read-mode-test
  (testing ":full-read mode (default behavior)"
    ;; Explicitly set to :full-read (though it's the default)
    (set-write-file-guard! :full-read)

    (let [file-path (create-test-file! "full-read-test.clj"
                                       "(ns test)\n(defn hello [] :world)")]

      (testing "collapsed read should NOT allow editing"
        ;; Perform collapsed read
        (let [read-result (read-file-collapsed! file-path)]
          (is (not (:error read-result))))

        ;; Try to edit - should fail
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (false? (:success edit-result)))
          (is (str/includes? (:error edit-result) "File has been modified since last read"))))

      (testing "full read should allow editing"
        ;; Perform full read
        (let [read-result (read-file-full! file-path)]
          (is (not (:error read-result))))

        ;; Try to edit - should succeed
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (true? (:success edit-result)))
          (is (str/includes? (slurp file-path) "goodbye"))))

      (testing "external modification should require re-reading"
        ;; Modify file externally
        (Thread/sleep 100)
        (spit file-path "(ns test)\n(defn external [] :change)")

        ;; Try to edit - should fail
        (let [edit-result (edit-file! file-path "external" "internal")]
          (is (false? (:success edit-result)))
          (is (str/includes? (:error edit-result) "File has been modified since last read")))))))

;; Tests for :partial-read mode
(deftest partial-read-mode-test
  (testing ":partial-read mode"
    ;; Set to :partial-read
    (set-write-file-guard! :partial-read)

    (let [file-path (create-test-file! "partial-read-test.clj"
                                       "(ns test)\n(defn hello [] :world)")]

      (testing "collapsed read SHOULD allow editing"
        ;; Perform collapsed read
        (let [read-result (read-file-collapsed! file-path)]
          (is (not (:error read-result))))

        ;; Try to edit - should succeed
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (true? (:success edit-result)))
          (is (str/includes? (slurp file-path) "goodbye"))))

      (testing "full read should also allow editing"
        ;; Reset file content
        (spit file-path "(ns test)\n(defn hello [] :world)")

        ;; Perform full read
        (let [read-result (read-file-full! file-path)]
          (is (not (:error read-result))))

        ;; Try to edit - should succeed
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (true? (:success edit-result)))
          (is (str/includes? (slurp file-path) "goodbye"))))

      (testing "external modification should still require re-reading"
        ;; Modify file externally
        (Thread/sleep 100)
        (spit file-path "(ns test)\n(defn external [] :change)")

        ;; Try to edit - should fail
        (let [edit-result (edit-file! file-path "external" "internal")]
          (is (false? (:success edit-result)))
          (is (str/includes? (:error edit-result) "File has been modified since last read")))))))

;; Tests for false mode (disabled)
(deftest false-mode-test
  (testing "false mode (timestamp checking disabled)"
    ;; Set to false
    (set-write-file-guard! false)

    (let [file-path (create-test-file! "false-mode-test.clj"
                                       "(ns test)\n(defn hello [] :world)")]

      (testing "editing without reading should succeed"
        ;; Try to edit without any read - should succeed
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (true? (:success edit-result)))
          (is (str/includes? (slurp file-path) "goodbye"))))

      (testing "editing after external modification should succeed"
        ;; Modify file externally
        (Thread/sleep 100)
        (spit file-path "(ns test)\n(defn external [] :change)")

        ;; Try to edit - should succeed (no timestamp check)
        (let [edit-result (edit-file! file-path "external" "internal")]
          (is (true? (:success edit-result)))
          (is (str/includes? (slurp file-path) "internal"))))

      (testing "file-write should also work without reading"
        (let [write-result (write-file! file-path "(ns test)\n(defn written [] :content)")]
          (is (true? (:success write-result)))
          (is (str/includes? (slurp file-path) "written")))))))

;; Test default behavior when config not set
(deftest default-behavior-test
  (testing "default behavior when write-file-guard not configured"
    ;; Don't set write-file-guard, use default

    (let [file-path (create-test-file! "default-test.clj"
                                       "(ns test)\n(defn hello [] :world)")]

      (testing "should behave as :full-read by default"
        ;; Perform collapsed read
        (let [read-result (read-file-collapsed! file-path)]
          (is (not (:error read-result))))

        ;; Try to edit - should fail (default is :full-read)
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (false? (:success edit-result)))
          (is (str/includes? (:error edit-result) "File has been modified since last read")))

        ;; Perform full read
        (let [read-result (read-file-full! file-path)]
          (is (not (:error read-result))))

        ;; Try to edit - should succeed
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (true? (:success edit-result)))
          (is (str/includes? (slurp file-path) "goodbye")))))))

;; Test configuration validation
(deftest config-validation-test
  (testing "invalid write-file-guard values"
    ;; Set an invalid value
    (config/set-config! *test-client-atom* :write-file-guard :invalid-mode)

    ;; Create test file
    (let [file-path (create-test-file! "validation-test.clj"
                                       "(ns test)\n(defn hello [] :world)")]

      (testing "should fall back to default behavior (:full-read)"
        ;; Perform collapsed read
        (let [read-result (read-file-collapsed! file-path)]
          (is (not (:error read-result))))

        ;; Try to edit - should fail (falls back to :full-read)
        (let [edit-result (edit-file! file-path "hello" "goodbye")]
          (is (false? (:success edit-result)))
          (is (str/includes? (:error edit-result) "File has been modified since last read")))))))

;; Test interaction between different tools
(deftest multi-tool-interaction-test
  (testing "write-file-guard affects multiple editing tools consistently"
    (set-write-file-guard! :partial-read)

    (let [file-path (create-test-file! "multi-tool-test.clj"
                                       "(ns test)\n(defn hello [] :world)")]

      ;; Perform collapsed read
      (let [read-result (read-file-collapsed! file-path)]
        (is (not (:error read-result))))

      (testing "file-edit should work after collapsed read"
        (let [edit-result (edit-file! file-path "hello" "edited")]
          (is (true? (:success edit-result)))
          (is (str/includes? (slurp file-path) "edited"))))

      ;; Reset file
      (spit file-path "(ns test)\n(defn hello [] :world)")
      ;; Update timestamp since we modified externally
      (file-timestamps/update-file-timestamp-to-current-mtime! *test-client-atom* file-path)

      (testing "file-write should also respect the configuration"
        (let [write-result (write-file! file-path "(ns test)\n(defn written [] :new)")]
          (is (true? (:success write-result)))
          (is (str/includes? (slurp file-path) "written")))))))
