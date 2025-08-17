(ns clojure-mcp.config.port-discovery-test
  "Comprehensive tests for port discovery configuration options"
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [clojure-mcp.config :as config]
   [clojure-mcp.core :as core]))

;; =============================================================================
;; Test Utilities
;; =============================================================================

(defn temp-config-file
  "Create a temporary config file with given content and return its path"
  [config-content]
  (let [temp-file (java.io.File/createTempFile "test-config" ".edn")]
    (.deleteOnExit temp-file)
    (spit temp-file (pr-str config-content))
    (.getAbsolutePath temp-file)))

(defn test-opts
  "Create test options with config file"
  [config-content]
  {:config-file (temp-config-file config-content)
   :project-dir "/tmp"})

(defn test-validate-options
  "Helper to validate options with config, mimicking the new flow"
  [opts]
  (let [working-dir (config/working-dir (:project-dir opts))
        config (config/read-config-from-file (:config-file opts) working-dir)]
    (core/validate-options opts config)))

;; Test helpers
(defn get-parse-nrepl-port
  "Returns the :parse-nrepl-port configuration value.
   This controls whether to parse the nREPL port from :start-nrepl-cmd stdout.
   Only valid when :start-nrepl-cmd is also configured.
   Returns false if not configured."
  [nrepl-client-map]
  (boolean (config/get-config nrepl-client-map :parse-nrepl-port)))

(defn get-read-nrepl-port-file
  "Returns the :read-nrepl-port-file configuration value.
   This controls whether to read the nREPL port from .nrepl-port file.
   Returns false if not configured."
  [nrepl-client-map]
  (boolean (config/get-config nrepl-client-map :read-nrepl-port-file)))

;; =============================================================================
;; Config Processing Tests 
;; =============================================================================

(deftest get-parse-nrepl-port-test
  (testing "returns false when not configured"
    (let [nrepl-client-map {}]
      (is (false? (get-parse-nrepl-port nrepl-client-map)))))

  (testing "returns true when explicitly configured"
    (let [nrepl-client-map {::config/config {:parse-nrepl-port true}}]
      (is (true? (get-parse-nrepl-port nrepl-client-map)))))

  (testing "returns false when explicitly disabled"
    (let [nrepl-client-map {::config/config {:parse-nrepl-port false}}]
      (is (false? (get-parse-nrepl-port nrepl-client-map))))))

(deftest get-read-nrepl-port-file-test
  (testing "returns false when not configured"
    (let [nrepl-client-map {}]
      (is (false? (get-read-nrepl-port-file nrepl-client-map)))))

  (testing "returns true when explicitly configured"
    (let [nrepl-client-map {::config/config {:read-nrepl-port-file true}}]
      (is (true? (get-read-nrepl-port-file nrepl-client-map)))))

  (testing "returns false when explicitly disabled"
    (let [nrepl-client-map {::config/config {:read-nrepl-port-file false}}]
      (is (false? (get-read-nrepl-port-file nrepl-client-map))))))

(deftest process-config-parse-nrepl-port-test
  (testing "validates parse-nrepl-port is a boolean"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid Config: :parse-nrepl-port value: true - must be a boolean"
         (config/process-config {:parse-nrepl-port "true"} "/tmp"))))

  (testing "validates parse-nrepl-port requires start-nrepl-cmd"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"parse-nrepl-port requires start-nrepl-cmd to be set"
         (config/process-config {:parse-nrepl-port true} "/tmp"))))

  (testing "accepts valid parse-nrepl-port with start-nrepl-cmd"
    (let [result (config/process-config
                  {:start-nrepl-cmd "lein repl"
                   :parse-nrepl-port true}
                  "/tmp")]
      (is (true? (:parse-nrepl-port result)))))

  (testing "accepts parse-nrepl-port false with start-nrepl-cmd"
    (let [result (config/process-config
                  {:start-nrepl-cmd "lein repl"
                   :parse-nrepl-port false}
                  "/tmp")]
      (is (false? (:parse-nrepl-port result))))))

(deftest process-config-read-nrepl-port-file-test
  (testing "validates read-nrepl-port-file is a boolean"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid Config: :read-nrepl-port-file value: true - must be a boolean"
         (config/process-config {:read-nrepl-port-file "true"} "/tmp"))))

  (testing "accepts valid read-nrepl-port-file true"
    (let [result (config/process-config
                  {:read-nrepl-port-file true}
                  "/tmp")]
      (is (true? (:read-nrepl-port-file result)))))

  (testing "accepts read-nrepl-port-file false"
    (let [result (config/process-config
                  {:read-nrepl-port-file false}
                  "/tmp")]
      (is (false? (:read-nrepl-port-file result)))))

  (testing "accepts read-nrepl-port-file independently"
    (let [result (config/process-config
                  {:read-nrepl-port-file true
                   :some-other-option "value"}
                  "/tmp")]
      (is (true? (:read-nrepl-port-file result))))))

;; =============================================================================
;; Valid Configuration Combinations
;; =============================================================================

(deftest valid-config-combinations-test
  (testing "explicit port always valid"
    (let [opts {:port 7888 :project-dir "/tmp"}]
      (is (= opts (test-validate-options opts)))))

  (testing "start-nrepl-cmd with parse-nrepl-port true"
    (let [opts (test-opts {:start-nrepl-cmd "lein repl"
                           :parse-nrepl-port true})]
      (is (some? (test-validate-options opts)))))

  (testing "read-nrepl-port-file true standalone"
    (let [opts (test-opts {:read-nrepl-port-file true})]
      (is (some? (test-validate-options opts)))))

  (testing "start-nrepl-cmd with read-nrepl-port-file true (coordinated)"
    (let [opts (test-opts {:start-nrepl-cmd "lein repl"
                           :read-nrepl-port-file true})]
      (is (some? (test-validate-options opts)))))

  (testing "all options together (should prefer coordinated)"
    (let [opts (test-opts {:start-nrepl-cmd "lein repl"
                           :parse-nrepl-port true
                           :read-nrepl-port-file true})]
      (is (some? (test-validate-options opts)))))

  (testing "explicit port overrides all discovery options"
    (let [opts {:port 7888
                :config-file (temp-config-file {:start-nrepl-cmd "lein repl"
                                                :parse-nrepl-port true
                                                :read-nrepl-port-file true})
                :project-dir "/tmp"}]
      (is (= opts (test-validate-options opts))))))

;; =============================================================================
;; Invalid Configuration Combinations  
;; =============================================================================

(deftest invalid-config-combinations-test
  (testing "no port and no config file"
    (let [opts {:project-dir "/tmp"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No :port provided and no port discovery options enabled"
           (test-validate-options opts)))))

  (testing "start-nrepl-cmd alone without port discovery options"
    (let [opts (test-opts {:start-nrepl-cmd "lein repl"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No :port provided and no port discovery options enabled"
           (test-validate-options opts)))))

  (testing "start-nrepl-cmd with explicit parse-nrepl-port false"
    (let [opts (test-opts {:start-nrepl-cmd "lein repl"
                           :parse-nrepl-port false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No :port provided and no port discovery options enabled"
           (test-validate-options opts)))))

  (testing "parse-nrepl-port true without start-nrepl-cmd"
    (let [opts (test-opts {:parse-nrepl-port true})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"parse-nrepl-port requires start-nrepl-cmd to be set"
           (config/process-config {:parse-nrepl-port true} "/tmp")))))

  (testing "both options explicitly disabled"
    (let [opts (test-opts {:start-nrepl-cmd "lein repl"
                           :parse-nrepl-port false
                           :read-nrepl-port-file false})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No :port provided and no port discovery options enabled"
           (test-validate-options opts)))))

  (testing "invalid parse-nrepl-port value"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid Config: :parse-nrepl-port value: true - must be a boolean"
         (config/process-config {:start-nrepl-cmd "lein repl"
                                 :parse-nrepl-port "true"} "/tmp"))))

  (testing "invalid read-nrepl-port-file value"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid Config: :read-nrepl-port-file value: true - must be a boolean"
         (config/process-config {:read-nrepl-port-file "true"} "/tmp"))))

  (testing "invalid start-nrepl-cmd value"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid Config: :start-nrepl-cmd value: 123 - must be a string command"
         (config/process-config {:start-nrepl-cmd 123} "/tmp")))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest edge-cases-test
  (testing "empty config file"
    (let [opts (test-opts {})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No :port provided and no port discovery options enabled"
           (test-validate-options opts)))))

  (testing "config with unrelated options"
    (let [opts (test-opts {:some-unrelated-option "value"
                           :another-option 42})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No :port provided and no port discovery options enabled"
           (test-validate-options opts)))))

  (testing "empty start-nrepl-cmd string"
    (let [opts (test-opts {:start-nrepl-cmd ""
                           :parse-nrepl-port true})]
      (is (some? (test-validate-options opts)))))

  (testing "start-nrepl-cmd with whitespace"
    (let [opts (test-opts {:start-nrepl-cmd "  lein repl  "
                           :parse-nrepl-port true})]
      (is (some? (test-validate-options opts)))))

  (testing "nil values in config"
    (let [config-map {:start-nrepl-cmd nil
                      :parse-nrepl-port nil
                      :read-nrepl-port-file nil}
          result (config/process-config config-map "/tmp")]
      (is (nil? (:start-nrepl-cmd result)))
      (is (false? (get-parse-nrepl-port {::config/config result})))
      (is (false? (get-read-nrepl-port-file {::config/config result}))))))
