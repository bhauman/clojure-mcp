(ns clojure-mcp.agent.langchain.model-test
  "Tests for the simplified model.clj that uses langchain4clj."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure-mcp.agent.langchain.model :as model]
   [clojure-mcp.config :as config]
   [langchain4clj.presets :as presets]
   [langchain4clj.core :as lc])
  (:import
   [dev.langchain4j.model.anthropic AnthropicChatModel]
   [dev.langchain4j.model.openai OpenAiChatModel]))

(deftest test-available-models
  (testing "Available models returns langchain4clj presets"
    (let [models (model/available-models)]
      ;; Should have OpenAI models
      (is (some #{:openai/gpt-4o} models))
      (is (some #{:openai/o4-mini} models))
      ;; Should have Anthropic models
      (is (some #{:anthropic/claude-sonnet-4} models))
      (is (some #{:anthropic/claude-3-5-haiku} models)))))

(deftest test-get-provider
  (testing "Provider extraction from model keys"
    (is (= :openai (model/get-provider :openai/o4-mini)))
    (is (= :google (model/get-provider :google/gemini-2-5-flash)))
    (is (= :anthropic (model/get-provider :anthropic/claude-sonnet-4)))
    (is (= :custom (model/get-provider :custom/my-model)))))

(deftest test-create-model-from-config-with-user-models
  (testing "User config takes precedence over presets"
    (binding [lc/*env-overrides* {"OPENAI_API_KEY" "test-key-value"}]
      (let [user-models {:openai/my-custom {:provider :openai
                                            :model "gpt-4-turbo"
                                            :temperature 0.5
                                            :api-key [:env "OPENAI_API_KEY"]}}
            nrepl-client-map {::config/config {:models user-models}}
            result-model (model/create-model-from-config
                          nrepl-client-map
                          :openai/my-custom)]
        (is (instance? OpenAiChatModel result-model)))))

  (testing "Falls back to langchain4clj presets when not in user config"
    (binding [lc/*env-overrides* {"OPENAI_API_KEY" "test-key-value"}]
      (let [nrepl-client-map {::config/config {:models {}}}
            ;; Use preset with explicit api-key override
            result-model (model/create-model-from-config
                          nrepl-client-map
                          :openai/gpt-4o
                          {:api-key [:env "OPENAI_API_KEY"]})]
        (is (instance? OpenAiChatModel result-model)))))

  (testing "Works with Anthropic models"
    (binding [lc/*env-overrides* {"ANTHROPIC_API_KEY" "test-key-value"}]
      (let [nrepl-client-map {::config/config {:models {}}}
            result-model (model/create-model-from-config
                          nrepl-client-map
                          :anthropic/claude-sonnet-4
                          {:api-key [:env "ANTHROPIC_API_KEY"]})]
        (is (instance? AnthropicChatModel result-model)))))

  (testing "Unknown model key throws exception"
    (let [nrepl-client-map {::config/config {:models {}}}]
      (is (thrown-with-msg? Exception #"Unknown model key"
                            (model/create-model-from-config
                             nrepl-client-map
                             :unknown/model))))))

(deftest test-build-model-backward-compat
  (testing "build-model creates models from presets with api-key"
    (binding [lc/*env-overrides* {"OPENAI_API_KEY" "test-key-value"
                                  "ANTHROPIC_API_KEY" "test-key-value"}]
      (let [openai-model (model/build-model :openai/gpt-4o
                                            {:api-key [:env "OPENAI_API_KEY"]})]
        (is (instance? OpenAiChatModel openai-model)))

      (let [anthropic-model (model/build-model :anthropic/claude-sonnet-4
                                               {:api-key [:env "ANTHROPIC_API_KEY"]})]
        (is (instance? AnthropicChatModel anthropic-model))))))

(deftest test-model-name-mapping
  (testing "model-name is mapped to model for langchain4clj"
    ;; Old clojure-mcp configs use :model-name, langchain4clj uses :model
    (binding [lc/*env-overrides* {"OPENAI_API_KEY" "test-key-value"}]
      (let [user-models {:openai/legacy {:provider :openai
                                         :model-name "gpt-4o" ; Old style
                                         :temperature 0.5
                                         :api-key [:env "OPENAI_API_KEY"]}}
            nrepl-client-map {::config/config {:models user-models}}
            result-model (model/create-model-from-config
                          nrepl-client-map
                          :openai/legacy)]
        (is (instance? OpenAiChatModel result-model))))))

(deftest test-get-tool-model
  (testing "Returns nil when tool config not found"
    (let [nrepl-client-map {::config/config {:tools-config {}}}]
      (is (nil? (model/get-tool-model nrepl-client-map :nonexistent-tool)))))

  (testing "Returns nil when model key not in tool config"
    (let [nrepl-client-map {::config/config {:tools-config {:my-tool {}}}}]
      (is (nil? (model/get-tool-model nrepl-client-map :my-tool)))))

  (testing "Creates model when tool config has valid user-defined model"
    (binding [lc/*env-overrides* {"OPENAI_API_KEY" "test-key-value"}]
      (let [nrepl-client-map {::config/config
                              {:tools-config {:dispatch_agent {:model :openai/my-model}}
                               :models {:openai/my-model {:provider :openai
                                                          :model "gpt-4o"
                                                          :api-key [:env "OPENAI_API_KEY"]}}}}
            result-model (model/get-tool-model nrepl-client-map :dispatch_agent)]
        (is (instance? OpenAiChatModel result-model))))))
