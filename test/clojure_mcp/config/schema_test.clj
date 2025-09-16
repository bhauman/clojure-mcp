(ns clojure-mcp.config.schema-test
  "Tests for configuration schema validation"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure-mcp.config.schema :as schema]
            [malli.core :as m]))

;; ==============================================================================
;; Test Data
;; ==============================================================================

(def valid-minimal-config
  "Minimal valid configuration"
  {})

(def valid-full-config
  "Complete valid configuration with all fields"
  {:allowed-directories ["." "src" "test"]
   :emacs-notify false
   :write-file-guard :partial-read
   :cljfmt true
   :bash-over-nrepl true
   :nrepl-env-type :clj
   :scratch-pad-load false
   :scratch-pad-file "scratch.edn"
   :models {:openai/gpt4 {:model-name "gpt-4"
                          :temperature 0.7
                          :max-tokens 2048
                          :api-key [:env "OPENAI_API_KEY"]}
            :anthropic/claude {:provider :anthropic
                               :model-name "claude-3-5-sonnet"
                               :thinking {:enabled true
                                          :budget-tokens 4096}}}
   :tools-config {:dispatch_agent {:model :openai/gpt4}}
   :agents [{:id :my-agent
             :name "My Agent"
             :description "A custom agent"
             :model :openai/gpt4
             :enable-tools [:read-file :grep]}]
   :mcp-client "claude-desktop"
   :dispatch-agent-context true
   :enable-tools [:clojure-eval :read-file]
   :disable-tools ["bash"]
   :enable-prompts ["clojure_repl_system_prompt"]
   :disable-prompts ["scratch-pad-save-as"]
   :enable-resources ["README.md"]
   :disable-resources ["CLAUDE.md"]
   :resources {"my-doc" {:description "My documentation"
                         :file-path "doc/my-doc.md"
                         :url "custom://my-doc"
                         :mime-type "text/markdown"}}
   :prompts {"my-prompt" {:description "My custom prompt"
                          :content "Hello {{name}}"
                          :args [{:name "name"
                                  :description "User name"
                                  :required? true}]}}})

;; ==============================================================================
;; Valid Configuration Tests
;; ==============================================================================

(deftest valid-configurations-test
  (testing "Minimal config should be valid"
    (is (schema/valid? valid-minimal-config)))

  (testing "Full config should be valid"
    (binding [schema/*validate-env-vars* false]
      (is (schema/valid? valid-full-config))))

  (testing "Config with only core settings"
    (is (schema/valid? {:allowed-directories ["."]
                        :cljfmt true
                        :bash-over-nrepl false})))

  (testing "Config with dispatch-agent-context as file list"
    (is (schema/valid? {:dispatch-agent-context ["doc/overview.md" "README.md"]})))

  (testing "Config with environment variable references"
    ;; Disable env var validation for testing
    (binding [schema/*validate-env-vars* false]
      (is (schema/valid? {:models {:openai/test {:model-name [:env "MODEL_NAME"]
                                                 :api-key [:env "API_KEY"]
                                                 :base-url [:env "BASE_URL"]}}}))))

  (testing "Config with all nrepl-env-type values"
    (doseq [env-type [:clj :bb :basilisp :scittle]]
      (is (schema/valid? {:nrepl-env-type env-type})))))

;; ==============================================================================
;; Invalid Configuration Tests
;; ==============================================================================

(deftest invalid-write-file-guard-test
  (testing "Invalid write-file-guard value"
    (let [config {:write-file-guard :invalid-value}
          errors (schema/explain-config config)]
      (is (some? errors))
      (is (contains? errors :write-file-guard)))))

(deftest invalid-model-config-test
  (testing "Model without required model-name"
    (let [config {:models {:openai/bad {}}}
          errors (schema/explain-config config)]
      (is (some? errors))))

  (testing "Model with invalid temperature"
    (let [config {:models {:openai/bad {:model-name "gpt-4"
                                        :temperature 3.0}}}
          errors (schema/explain-config config)]
      (is (some? errors))))

  (testing "Model with negative max-tokens"
    (let [config {:models {:openai/bad {:model-name "gpt-4"
                                        :max-tokens -100}}}
          errors (schema/explain-config config)]
      (is (some? errors)))))

(deftest invalid-agent-config-test
  (testing "Agent missing required fields"
    (let [config {:agents [{:id :agent1}]}
          errors (schema/explain-config config)]
      (is (some? errors))))

  (testing "Agent with non-keyword id"
    (let [config {:agents [{:id "agent1"
                            :name "Agent"
                            :description "Desc"}]}
          errors (schema/explain-config config)]
      (is (some? errors)))))

(deftest invalid-resource-config-test
  (testing "Resource missing description"
    (let [config {:resources {"my-res" {:file-path "file.md"}}}
          errors (schema/explain-config config)]
      (is (some? errors))
      (is (-> errors :resources (get "my-res") :description)))))

(deftest invalid-prompt-config-test
  (testing "Prompt missing description"
    (let [config {:prompts {"my-prompt" {:content "Hello"}}}
          errors (schema/explain-config config)]
      (is (some? errors)))))

;; ==============================================================================
;; Typo Detection Tests
;; ==============================================================================

(deftest typo-detection-test
  (testing "Detects typo in configuration key"
    (let [config {:write-file-gaurd :full-read}
          errors (schema/explain-config config)]
      (is (some? errors))
      ;; The error should suggest the correct key
      (is (re-find #"write-file-guard" (str errors)))))

  (testing "Detects typo in nested key"
    (let [config {:models {:openai/test {:model-nam "gpt-4"}}}
          errors (schema/explain-config config)]
      (is (some? errors))
      (is (re-find #"model-name" (str errors))))))

;; ==============================================================================
;; Example Configuration File Tests
;; ==============================================================================

(deftest example-config-files-test
  (testing "Example configurations should be valid if they exist"
    (let [example-dir (io/file "resources/configs")
          example-files (when (.exists example-dir)
                          (filter #(str/ends-with? (.getName %) ".edn")
                                  (.listFiles example-dir)))]
      ;; Disable env var validation for testing example files
      (binding [schema/*validate-env-vars* false]
        (doseq [file example-files]
          (testing (str "File: " (.getName file))
            (let [config (edn/read-string (slurp file))]
              (when-let [errors (schema/explain-config config)]
                (println "Validation errors for" (.getName file) ":")
                (println errors))
              (is (schema/valid? config)
                  (str "Invalid config in " (.getName file))))))))))

;; ==============================================================================
;; Edge Cases
;; ==============================================================================

(deftest edge-cases-test
  (testing "Empty lists for filtering"
    (is (schema/valid? {:enable-tools []
                        :disable-tools []
                        :enable-prompts []
                        :disable-prompts []})))

  (testing "Mixed keyword and string tool IDs"
    (is (schema/valid? {:enable-tools [:clojure-eval "read-file" :bash]})))

  (testing "Nil values for optional maybe fields"
    (is (schema/valid? {:mcp-client nil})))

  (testing "Complex nested thinking config"
    (is (schema/valid? {:models {:anthropic/test
                                 {:model-name "claude"
                                  :thinking {:enabled true
                                             :return true
                                             :send false
                                             :budget-tokens 8192}}}}))))

;; ==============================================================================
;; Error Message Quality Tests
;; ==============================================================================

(deftest error-message-quality-test
  (testing "Error messages for enum values list options"
    (let [config {:nrepl-env-type :invalid}
          errors (schema/explain-config config)]
      (is (some? errors))
      ;; Should list valid options
      (is (or (re-find #":clj" (str errors))
              (re-find #"clj" (str errors)))))))
