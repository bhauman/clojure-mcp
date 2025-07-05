(ns clojure-mcp.tools.scratch-pad.config-test
  "Consolidated tests for scratch pad configuration functionality"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [clojure-mcp.tools.scratch-pad.tool :as sut]
   [clojure-mcp.tools.scratch-pad.config :as sp-config]
   [clojure-mcp.config :as config]
   [clojure-mcp.tool-system :as tool-system]))

(def test-dir "/tmp/clojure-mcp-config-test")
(def test-working-dir (str test-dir "/" (System/currentTimeMillis)))

(defn create-test-atom
  "Creates a test atom with config data"
  [config-map]
  (atom (merge {::config/config config-map}
               {})))

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

(defn write-test-config [config-map]
  (sp-config/write-config-file test-working-dir config-map))

(defn read-test-config []
  (sp-config/read-config-file test-working-dir))

(defn file-exists? [filename]
  (.exists (io/file test-working-dir ".clojure-mcp" filename)))

;; Tests from config_init_test.clj

(deftest config-based-initialization-test
  (testing "scratch-pad-tool initializes persistence from config"
    ;; Write config file
    (write-test-config {:scratch-pad-load true
                        :scratch-pad-file "test_scratch.edn"
                        :other-config "preserved"})

    ;; Create atom with config
    (let [atom (create-test-atom {:scratch-pad-load true
                                  :scratch-pad-file "test_scratch.edn"})
          tool (sut/scratch-pad-tool atom test-working-dir)]

      ;; Should have enabled persistence
      (is (contains? @atom ::sut/persistence-enabled))
      (is (= true (::sut/persistence-enabled @atom)))
      (is (= "test_scratch.edn" (::sut/persistence-filename @atom)))

      ;; Should have created persistence file
      (Thread/sleep 200) ; Let watcher save
      (is (file-exists? "test_scratch.edn"))

      ;; Verify we can store and retrieve data
      (tool-system/execute-tool
       {:tool-type :scratch-pad
        :nrepl-client-atom atom
        :working-directory test-working-dir}
       {:op "set_path"
        :path ["test-key"]
        :value "test-value"
        :explanation "test data"})

      (Thread/sleep 200) ; Let watcher save
      (is (file-exists? "test_scratch.edn")))))

(deftest config-disabled-by-default-test
  (testing "persistence is disabled when config is false or missing"
    ;; Test with explicit false
    (let [atom (create-test-atom {:scratch-pad-load false})
          tool (sut/scratch-pad-tool atom test-working-dir)]
      (is (not (contains? @atom ::sut/persistence-enabled))))

    ;; Test with missing config
    (let [atom (create-test-atom {})
          tool (sut/scratch-pad-tool atom test-working-dir)]
      (is (not (contains? @atom ::sut/persistence-enabled))))))

(deftest persistence-config-updates-config-file-test
  (testing "persistence_config operation updates config.edn file"
    ;; Start with no config file
    (let [atom (create-test-atom {})
          tool-config {:tool-type :scratch-pad
                       :nrepl-client-atom atom
                       :working-directory test-working-dir}]

      ;; Enable persistence via tool
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "persistence_config"
                     :enabled true
                     :filename "runtime.edn"
                     :explanation "enable via tool"})]
        (is (= false (:error result)))
        (is (= true (get-in result [:result :enabled])))

        ;; Check config file was created
        (let [config (read-test-config)]
          (is (= true (:scratch-pad-load config)))
          (is (= "runtime.edn" (:scratch-pad-file config))))

        ;; Check config in atom was updated
        (is (= true (config/get-scratch-pad-load @atom)))
        (is (= "runtime.edn" (config/get-scratch-pad-file @atom)))))))

(deftest persistence-config-preserves-other-config-test
  (testing "persistence_config preserves other configuration values"
    ;; Write config with other values
    (write-test-config {:allowed-directories ["/tmp" "/home"]
                        :emacs-notify true
                        :scratch-pad-load false})

    (let [atom (create-test-atom (read-test-config))
          tool-config {:tool-type :scratch-pad
                       :nrepl-client-atom atom
                       :working-directory test-working-dir}]

      ;; Enable persistence
      (tool-system/execute-tool
       tool-config
       {:op "persistence_config"
        :enabled true
        :explanation "enable"})

      ;; Check other config values preserved
      (let [config (read-test-config)]
        (is (= ["/tmp" "/home"] (:allowed-directories config)))
        (is (= true (:emacs-notify config)))
        (is (= true (:scratch-pad-load config)))))))

;; Tests from config_integration_test.clj

(deftest config-and-tool-integration-test
  (testing "Config-based and tool-based approaches work together"
    ;; Start with config-based persistence
    (write-test-config {:scratch-pad-load true
                        :scratch-pad-file "config-based.edn"})

    (let [atom (create-test-atom {:scratch-pad-load true
                                  :scratch-pad-file "config-based.edn"})
          tool (sut/scratch-pad-tool atom test-working-dir)
          tool-config {:tool-type :scratch-pad
                       :nrepl-client-atom atom
                       :working-directory test-working-dir}]

      ;; Should have initialized from config
      (is (= true (::sut/persistence-enabled @atom)))
      (is (= "config-based.edn" (::sut/persistence-filename @atom)))

      ;; Add some data
      (tool-system/execute-tool
       tool-config
       {:op "set_path"
        :path ["config-data"]
        :value "from config"
        :explanation "test"})

      (Thread/sleep 200)
      (is (file-exists? "config-based.edn"))

      ;; Change filename via tool
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "persistence_config"
                     :filename "tool-based.edn"
                     :explanation "change filename"})]
        (is (= true (get-in result [:result :enabled])))
        (is (= "tool-based.edn" (get-in result [:result :filename])))

        ;; Check config file was updated
        (let [config (read-test-config)]
          (is (= true (:scratch-pad-load config)))
          (is (= "tool-based.edn" (:scratch-pad-file config))))

        ;; Both files should exist
        (is (file-exists? "config-based.edn"))
        (is (file-exists? "tool-based.edn"))

        ;; New data should go to new file
        (tool-system/execute-tool
         tool-config
         {:op "set_path"
          :path ["tool-data"]
          :value "from tool"
          :explanation "test"})

        (Thread/sleep 200)

        ;; Verify data in new file
        (let [new-file-content (slurp (io/file test-working-dir ".clojure-mcp" "tool-based.edn"))]
          (is (re-find #"config-data" new-file-content))
          (is (re-find #"tool-data" new-file-content)))))))

(deftest restart-with-updated-config-test
  (testing "Restarting with updated config works correctly"
    ;; Start with no persistence and add some initial data
    (let [atom1 (create-test-atom {})
          tool1 (sut/scratch-pad-tool atom1 test-working-dir)
          tool-config {:tool-type :scratch-pad
                       :nrepl-client-atom atom1
                       :working-directory test-working-dir}]

      ;; Should not have persistence initially
      (is (not (contains? @atom1 ::sut/persistence-enabled)))

      ;; Add some initial data (in memory only at this point)
      (tool-system/execute-tool
       tool-config
       {:op "set_path"
        :path ["initial"]
        :value "in memory"
        :explanation "test"})

      ;; Enable persistence via tool - this saves current data including "initial"
      (tool-system/execute-tool
       tool-config
       {:op "persistence_config"
        :enabled true
        :filename "restart-test.edn"
        :explanation "enable"})

      ;; Add more data after persistence is enabled (also gets saved)
      (tool-system/execute-tool
       tool-config
       {:op "set_path"
        :path ["after-persistence"]
        :value "also saved"
        :explanation "test"})

      (Thread/sleep 200)

      ;; Simulate restart - create new atom with updated config
      (let [config (read-test-config)
            atom2 (create-test-atom config)
            tool2 (sut/scratch-pad-tool atom2 test-working-dir)]

        ;; Should have loaded persisted data and enabled persistence
        (is (= true (::sut/persistence-enabled @atom2)))
        (is (= "restart-test.edn" (::sut/persistence-filename @atom2)))

        ;; Check both data items were loaded
        (let [result1 (tool-system/execute-tool
                       {:tool-type :scratch-pad
                        :nrepl-client-atom atom2
                        :working-directory test-working-dir}
                       {:op "get_path"
                        :path ["initial"]
                        :explanation "check"})
              result2 (tool-system/execute-tool
                       {:tool-type :scratch-pad
                        :nrepl-client-atom atom2
                        :working-directory test-working-dir}
                       {:op "get_path"
                        :path ["after-persistence"]
                        :explanation "check"})]
          (is (= "in memory" (get-in result1 [:result :value])))
          (is (= "also saved" (get-in result2 [:result :value])))

          ;; Add new data after restart (should also be persisted)
          (tool-system/execute-tool
           {:tool-type :scratch-pad
            :nrepl-client-atom atom2
            :working-directory test-working-dir}
           {:op "set_path"
            :path ["after-restart"]
            :value "new data"
            :explanation "test"})

          (Thread/sleep 200)

          ;; Third restart to verify all data persists
          (let [config3 (read-test-config)
                atom3 (create-test-atom config3)
                tool3 (sut/scratch-pad-tool atom3 test-working-dir)]

            ;; All three data items should be present
            (let [result1 (tool-system/execute-tool
                           {:tool-type :scratch-pad
                            :nrepl-client-atom atom3
                            :working-directory test-working-dir}
                           {:op "get_path"
                            :path ["initial"]
                            :explanation "check"})
                  result2 (tool-system/execute-tool
                           {:tool-type :scratch-pad
                            :nrepl-client-atom atom3
                            :working-directory test-working-dir}
                           {:op "get_path"
                            :path ["after-persistence"]
                            :explanation "check"})
                  result3 (tool-system/execute-tool
                           {:tool-type :scratch-pad
                            :nrepl-client-atom atom3
                            :working-directory test-working-dir}
                           {:op "get_path"
                            :path ["after-restart"]
                            :explanation "check"})]
              (is (= "in memory" (get-in result1 [:result :value])))
              (is (= "also saved" (get-in result2 [:result :value])))
              (is (= "new data" (get-in result3 [:result :value]))))))))))

;; Additional config-specific tests

(deftest config-file-operations-test
  (testing "config file read/write operations"
    (testing "read non-existent config returns empty map"
      (is (= {} (sp-config/read-config-file test-working-dir))))

    (testing "write and read config file"
      (let [test-config {:scratch-pad-load true
                         :scratch-pad-file "test.edn"
                         :other-setting 42}]
        (is (true? (sp-config/write-config-file test-working-dir test-config)))
        (is (= test-config (sp-config/read-config-file test-working-dir)))))

    (testing "update-scratch-pad-config preserves other values"
      (write-test-config {:other-setting "preserved"
                          :another-one true})

      (sp-config/update-scratch-pad-config test-working-dir true "updated.edn")

      (let [config (read-test-config)]
        (is (= true (:scratch-pad-load config)))
        (is (= "updated.edn" (:scratch-pad-file config)))
        (is (= "preserved" (:other-setting config)))
        (is (= true (:another-one config)))))

    (testing "partial updates work correctly"
      (write-test-config {:scratch-pad-load false
                          :scratch-pad-file "old.edn"})

      ;; Update only enabled state
      (sp-config/update-scratch-pad-config test-working-dir true nil)
      (let [config (read-test-config)]
        (is (= true (:scratch-pad-load config)))
        (is (= "old.edn" (:scratch-pad-file config))))

      ;; Update only filename
      (sp-config/update-scratch-pad-config test-working-dir nil "new.edn")
      (let [config (read-test-config)]
        (is (= true (:scratch-pad-load config)))
        (is (= "new.edn" (:scratch-pad-file config)))))))