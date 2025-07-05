(ns clojure-mcp.tools.scratch-pad.persistence-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.scratch-pad.tool :as sut]
   [clojure.string :as string]))

(def test-dir "/tmp/clojure-mcp-test")
(def test-working-dir (str test-dir "/" (System/currentTimeMillis)))

(defn create-test-atom
  "Creates a test atom with optional initial data"
  ([]
   (atom {}))
  ([initial-data]
   (atom {::sut/scratch-pad initial-data})))

(defn cleanup-test-dir [f]
  ;; Create fresh test directory
  (let [dir (io/file test-working-dir)]
    (.mkdirs dir))

  ;; Run tests
  (f)

  ;; Cleanup
  (let [root (io/file test-dir)]
    (when (.exists root)
      ;; Delete recursively
      (doseq [file (reverse (file-seq root))]
        (.delete file)))))

(use-fixtures :each cleanup-test-dir)

(defn file-exists? [working-dir filename]
  (.exists (io/file working-dir ".clojure-mcp" filename)))

(defn read-file-content [working-dir filename]
  (let [file (io/file working-dir ".clojure-mcp" filename)]
    (when (.exists file)
      (slurp file))))

 ;; Watch helper functions for testing
(defn watch-exists?
  "Check if a specific watch exists on an atom."
  [atom watch-key]
  (contains? (.getWatches atom) watch-key))

(defn watch-count
  "Get the number of watches on an atom."
  [atom]
  (count (.getWatches atom)))

(defn assert-watch-exists
  "Assert that a watch exists on an atom."
  [atom watch-key]
  (is (watch-exists? atom watch-key)
      (str "Expected watch " watch-key " to exist on atom")))

(defn assert-watch-removed
  "Assert that a watch has been removed from an atom."
  [atom watch-key]
  (is (not (watch-exists? atom watch-key))
      (str "Expected watch " watch-key " to be removed from atom")))

(defn assert-watch-count
  "Assert the expected number of watches on an atom."
  [atom expected-count]
  (is (= expected-count (watch-count atom))
      (str "Expected " expected-count " watches, but found " (watch-count atom))))

(deftest persistence-config-validation-test
  (let [tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom (create-test-atom)
                     :working-directory test-working-dir}]

    (testing "persistence_config operation validation"
      (testing "missing operation throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter: op"
             (tool-system/validate-inputs
              tool-config
              {:explanation "test"}))))

      (testing "missing explanation throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter: explanation"
             (tool-system/validate-inputs
              tool-config
              {:op "persistence_config"}))))

      (testing "missing both enabled and filename parameters throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"persistence_config requires at least one parameter: enabled or filename"
             (tool-system/validate-inputs
              tool-config
              {:op "persistence_config"
               :explanation "test"}))))

      (testing "invalid enabled type throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"enabled must be a boolean"
             (tool-system/validate-inputs
              tool-config
              {:op "persistence_config"
               :enabled "true"
               :explanation "test"}))))

      (testing "invalid filename type throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"filename must be a string"
             (tool-system/validate-inputs
              tool-config
              {:op "persistence_config"
               :enabled true
               :filename 123
               :explanation "test"}))))

      (testing "valid inputs pass"
        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "persistence_config"
                       :enabled true
                       :explanation "test"})]
          (is (= "persistence_config" (:op result)))
          (is (= true (:enabled result)))
          (is (= "test" (:explanation result))))

        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "persistence_config"
                       :enabled false
                       :filename "custom.edn"
                       :explanation "test"})]
          (is (= false (:enabled result)))
          (is (= "custom.edn" (:filename result))))))))

(deftest status-operation-validation-test
  (let [tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom (create-test-atom)
                     :working-directory test-working-dir}]

    (testing "status operation validation"
      (testing "valid status operation"
        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "status"
                       :explanation "test"})]
          (is (= "status" (:op result)))
          (is (= "test" (:explanation result))))))))

(deftest enable-persistence-test
  (let [atom (create-test-atom {"existing" "data"})
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom atom
                     :working-directory test-working-dir}]

    (testing "enabling persistence"
      (testing "creates file and saves existing data"
        (let [result (tool-system/execute-tool
                      tool-config
                      {:op "persistence_config"
                       :enabled true
                       :explanation "enable persistence"})]
          (is (= false (:error result)))
          (is (contains? (:result result) :enabled))
          (is (= true (get-in result [:result :enabled])))
          (is (contains? (:result result) :filename))
          (is (= "scratch_pad.edn" (get-in result [:result :filename])))

          ;; Check file was created
          (is (file-exists? test-working-dir "scratch_pad.edn"))

          ;; Check existing data was saved
          (let [content (read-file-content test-working-dir "scratch_pad.edn")]
            (is (string/includes? content "existing"))
            (is (string/includes? content "data")))))

      ; Lock file test removed - lock file functionality dropped 

      (testing "subsequent changes are persisted"
        (tool-system/execute-tool
         tool-config
         {:op "set_path"
          :path ["new"]
          :value "persisted"
          :explanation "test"})

        ;; Give watcher time to save
        (Thread/sleep 100)

        (let [content (read-file-content test-working-dir "scratch_pad.edn")]
          (is (string/includes? content "new"))
          (is (string/includes? content "persisted")))))))

(deftest enable-persistence-with-custom-filename-test
  (let [atom (create-test-atom)
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom atom
                     :working-directory test-working-dir}]

    (testing "enabling persistence with custom filename"
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "persistence_config"
                     :enabled true
                     :filename "my_workspace.edn"
                     :explanation "enable with custom file"})]
        (is (= false (:error result)))
        (is (= "my_workspace.edn" (get-in result [:result :filename])))
        (is (file-exists? test-working-dir "my_workspace.edn"))))))

(deftest disable-persistence-test
  (let [atom (create-test-atom {"data" "to save"})
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom atom
                     :working-directory test-working-dir}]

    ;; First enable persistence
    (tool-system/execute-tool
     tool-config
     {:op "persistence_config"
      :enabled true
      :explanation "enable first"})

    ;; Verify watch was added when persistence enabled
    (assert-watch-exists atom ::sut/scratch-pad-persistence)

    (testing "disabling persistence"
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "persistence_config"
                     :enabled false
                     :explanation "disable persistence"})]
        (is (= false (:error result)))
        (is (= false (get-in result [:result :enabled])))

        ;; Verify watch was removed when persistence disabled
        (assert-watch-removed atom ::sut/scratch-pad-persistence)

        ;; Data should remain in memory
        (let [get-result (tool-system/execute-tool
                          tool-config
                          {:op "get_path"
                           :path ["data"]
                           :explanation "check data"})]
          (is (= "to save" (get-in get-result [:result :value]))))

        ;; File should still exist
        (is (file-exists? test-working-dir "scratch_pad.edn"))

        ;; But new changes should not be persisted
        (tool-system/execute-tool
         tool-config
         {:op "set_path"
          :path ["not-persisted"]
          :value "should not save"
          :explanation "test"})

        (Thread/sleep 100)

        (let [content (read-file-content test-working-dir "scratch_pad.edn")]
          (is (not (string/includes? content "not-persisted"))))))))

(deftest filename-change-test
  (let [atom (create-test-atom {"original" "data"})
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom atom
                     :working-directory test-working-dir}]

    ;; Enable with first filename
    (tool-system/execute-tool
     tool-config
     {:op "persistence_config"
      :enabled true
      :filename "first.edn"
      :explanation "enable with first file"})

    ;; Verify watch was added when persistence enabled
    (assert-watch-exists atom ::sut/scratch-pad-persistence)
    (let [initial-watch-count (watch-count atom)]

      (testing "changing filename while enabled"
        (let [result (tool-system/execute-tool
                      tool-config
                      {:op "persistence_config"
                       :filename "second.edn"
                       :explanation "change to second file"})]
          (is (= false (:error result)))
          (is (= true (get-in result [:result :enabled])))
          (is (= "second.edn" (get-in result [:result :filename])))

          ;; Watch should still exist (old removed, new added)
          (assert-watch-exists atom ::sut/scratch-pad-persistence)
          ;; Watch count should remain the same (remove + add = no change)
          (assert-watch-count atom initial-watch-count)

          ;; Both files should exist (Option B: preserve old file)
          (is (file-exists? test-working-dir "first.edn"))
          (is (file-exists? test-working-dir "second.edn"))

          ;; New file should have current data
          (let [content (read-file-content test-working-dir "second.edn")]
            (is (string/includes? content "original"))
            (is (string/includes? content "data")))

          ;; New changes go to new file
          (tool-system/execute-tool
           tool-config
           {:op "set_path"
            :path ["new-file-data"]
            :value "in second file"
            :explanation "test"})

          (Thread/sleep 100)

          (let [first-content (read-file-content test-working-dir "first.edn")
                second-content (read-file-content test-working-dir "second.edn")]
            (is (not (string/includes? first-content "new-file-data")))
            (is (string/includes? second-content "new-file-data"))))))))

(deftest status-operation-test
  (let [atom (create-test-atom {"test" "data"})
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom atom
                     :working-directory test-working-dir}]

    (testing "status when persistence disabled"
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "status"
                     :explanation "check status"})
            formatted (tool-system/format-results tool-config result)]
        (is (= false (:error result)))
        (is (string? (first (:result formatted))))
        (is (string/includes? (first (:result formatted)) "disabled"))))

    (testing "status when persistence enabled"
      ;; Enable persistence first
      (tool-system/execute-tool
       tool-config
       {:op "persistence_config"
        :enabled true
        :filename "status_test.edn"
        :explanation "enable for status test"})

      (Thread/sleep 100) ; Let file write complete

      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "status"
                     :explanation "check status"})
            formatted (tool-system/format-results tool-config result)]
        (is (= false (:error result)))
        (let [status-text (first (:result formatted))]
          (is (string/includes? status-text "enabled"))
          (is (string/includes? status-text "status_test.edn"))
          (is (string/includes? status-text "Data size:"))
          (is (string/includes? status-text "Entries:")))))))
(deftest corrupted-file-handling-test
  (let [atom (create-test-atom)
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom atom
                     :working-directory test-working-dir}]

    (testing "handling corrupted persistence file"
      ;; Create a corrupted file
      (let [dir (io/file test-working-dir ".clojure-mcp")]
        (.mkdirs dir)
        (spit (io/file dir "corrupted.edn") "{ invalid edn data ["))

      ;; Enable persistence with corrupted file
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "persistence_config"
                     :enabled true
                     :filename "corrupted.edn"
                     :explanation "enable with corrupted file"})]
        ;; Should succeed but store error
        (is (= false (:error result)))
        (is (= true (get-in result [:result :enabled]))))

      ;; First operation should show error  
      ;; Add small delay to ensure atom state is settled
      (Thread/sleep 50)
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "set_path"
                     :path ["test"]
                     :value "data"
                     :explanation "first op after corruption"})]
        (is (= false (:error result)))
        ;; Should see corruption error message
        (let [formatted (tool-system/format-results tool-config result)]
          ;; The load error should be included in the formatted result
          (is (some #(string/includes? % "Failed to load") (:result formatted)))))

      ;; Subsequent operations should not show error
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "get_path"
                     :path ["test"]
                     :explanation "second op"})]
        (is (= false (:error result)))
        (is (= "data" (get-in result [:result :value])))))))

(deftest runtime-save-error-test
  (let [atom (create-test-atom {"initial" "data"})
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom atom
                     :working-directory test-working-dir}]

    ;; Enable persistence
    (tool-system/execute-tool
     tool-config
     {:op "persistence_config"
      :enabled true
      :explanation "enable"})

    ;; Verify watch was added when persistence enabled
    (assert-watch-exists atom ::sut/scratch-pad-persistence)

    (testing "handling save errors at runtime"
      ;; Make the file read-only to cause save error
      (let [file (io/file test-working-dir ".clojure-mcp" "scratch_pad.edn")]
        ;; Ensure file exists first
        (Thread/sleep 100)
        (.setReadOnly file)

        ;; Try to save new data - should trigger save error
        (let [result (tool-system/execute-tool
                      tool-config
                      {:op "set_path"
                       :path ["will-fail"]
                       :value "save error"
                       :explanation "trigger save error"})]
          ;; Operation should succeed but persistence should be disabled
          (is (= false (:error result)))

          ;; Give watcher time to detect error and disable persistence
          (Thread/sleep 200)

          ;; Verify watch was removed due to save error
          (assert-watch-removed atom ::sut/scratch-pad-persistence)

          ;; Check persistence was disabled
          (let [status-result (tool-system/execute-tool
                               tool-config
                               {:op "status"
                                :explanation "check status"})
                formatted (tool-system/format-results tool-config status-result)]
            (is (string/includes? (first (:result formatted)) "disabled"))))

        ;; Restore permissions for cleanup
        (.setWritable file true)))))

; filename-edge-cases-test removed - not needed per user request

; lock-file-conflict-test removed - lock file functionality dropped per user request