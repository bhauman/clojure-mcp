(ns clojure-mcp.agent.langchain.model
  (:require
   [clojure.string :as string]
   [clojure-mcp.config.schema :as schema]
   [clojure-mcp.config :as config]
   [clojure.tools.logging :as log])
  (:import
   [dev.langchain4j.model.anthropic
    AnthropicChatModel
    AnthropicChatModelName]
   [dev.langchain4j.model.googleai
    GoogleAiGeminiChatModel
    GeminiThinkingConfig]
   [dev.langchain4j.model.openai
    OpenAiChatModel
    OpenAiChatRequestParameters]))

(def model-base
  "Base configuration for standard models"
  {:temperature 1
   :max-tokens 4096
   :max-retries 3
   :timeout 60000})

(def reasoning-model-base
  "Base configuration for reasoning models"
  {:temperature 1
   :max-tokens 8192
   :max-retries 3
   :timeout 120000})

(def thinking-base
  "Common thinking configuration for reasoning models"
  {:enabled true
   :return true
   :send true
   :effort :medium})

(def default-configs
  {;; OpenAI Models
   :openai/gpt-4o
   (merge model-base
          {:model-name "gpt-4o"})

   :openai/gpt-4-1
   (merge model-base
          {:model-name "gpt-4.1"})

   :openai/gpt-4-1-mini
   (merge model-base
          {:model-name "gpt-4.1-mini"})

   :openai/gpt-4-1-nano
   (merge model-base
          {:model-name "gpt-4.1-nano"})

   :openai/gpt-5
   (merge model-base
          {:model-name "gpt-5-2025-08-07"
           :thinking {:effort :medium}})

   :openai/gpt-5-mini
   (merge model-base
          {:model-name "gpt-5-mini-2025-08-07"
           :thinking {:effort :medium}})

   :openai/gpt-5-nano
   (merge model-base
          {:model-name "gpt-5-nano-2025-08-07"
           :thinking {:effort :medium}})

   :openai/o1
   (merge reasoning-model-base
          {:model-name "o1"
           :thinking {:effort :medium}})

   :openai/o1-mini
   (merge reasoning-model-base
          {:model-name "o1-mini"
           :thinking {:effort :medium}})

   :openai/o3
   (merge reasoning-model-base
          {:model-name "o3"
           :thinking {:effort :medium}})

   :openai/o3-mini
   (merge reasoning-model-base
          {:model-name "o3-mini"
           :thinking {:effort :medium}})

   :openai/o3-pro
   (merge reasoning-model-base
          {:model-name "o3-pro-2025-06-10"
           :thinking {:effort :medium}})

   :openai/o4-mini
   (merge model-base
          {:model-name "o4-mini"})

   :openai/o4-mini-reasoning
   (merge reasoning-model-base
          {:model-name "o4-mini"
           :thinking {:effort :medium}})

   ;; Google Models
   :google/gemini-2-5-flash-lite
   (merge model-base
          {:model-name "gemini-2.5-flash-lite"})

   :google/gemini-2-5-pro
   (merge model-base
          {:model-name "gemini-2.5-pro"})

   :google/gemini-2-5-flash
   (merge model-base
          {:model-name "gemini-2.5-flash"})

   :google/gemini-2-5-flash-reasoning
   (merge reasoning-model-base
          {:model-name "gemini-2.5-flash"
           :thinking thinking-base})

   :google/gemini-2-5-pro-reasoning
   (merge reasoning-model-base
          {:model-name "gemini-2.5-pro"
           :thinking thinking-base})

   ;; Anthropic Models
   :anthropic/claude-opus-4-1
   (merge model-base
          {:model-name "claude-opus-4-1-20250805"})

   :anthropic/claude-opus-4-1-reasoning
   (merge reasoning-model-base
          {:model-name "claude-opus-4-1-20250805"
           :thinking (merge thinking-base
                            {:budget-tokens 8192})})

   :anthropic/claude-opus-4
   (merge model-base
          {:model-name AnthropicChatModelName/CLAUDE_OPUS_4_20250514})

   :anthropic/claude-opus-4-reasoning
   (merge reasoning-model-base
          {:model-name AnthropicChatModelName/CLAUDE_OPUS_4_20250514
           :thinking (merge thinking-base
                            {:budget-tokens 8192})})

   :anthropic/claude-3-5-haiku
   (merge model-base
          {:model-name AnthropicChatModelName/CLAUDE_3_5_HAIKU_20241022
           :max-tokens 2048}) ; Haiku has lower default max tokens

   :anthropic/claude-sonnet-4
   (merge model-base
          {:model-name AnthropicChatModelName/CLAUDE_SONNET_4_20250514})

   :anthropic/claude-sonnet-4-reasoning
   (merge reasoning-model-base
          {:model-name AnthropicChatModelName/CLAUDE_SONNET_4_20250514
           :thinking (merge thinking-base
                            {:budget-tokens 4096})})})

(defn get-provider [model-key]
  (-> model-key namespace keyword))

(defn merge-with-defaults [model-key config-overrides]
  (let [defaults (get default-configs model-key {})]
    (merge defaults config-overrides)))

(def ^:dynamic *env-overrides*
  "Dynamic var for overriding environment variables in tests.
   When bound to a map, these values take precedence over System/getenv."
  nil)

(defn- get-env
  "Gets environment variable, checking *env-overrides* first for testing."
  [var-name]
  (or (when *env-overrides*
        (get *env-overrides* var-name))
      (System/getenv var-name)))

(defn- resolve-env-refs
  "Resolves [:env \"VAR_NAME\"] references to environment variable values.
   Recursively processes nested maps and collections.
   
   Note: Environment variables are always returned as strings. Use this
   pattern primarily for string values like :api-key and :base-url.
   For numeric values, consider using direct values in config.
   
   Examples:
   {:api-key [:env \"OPENAI_API_KEY\"]} => {:api-key \"actual-key-value\"}
   {:api-key \"direct-value\"} => {:api-key \"direct-value\"}"
  [config]
  (cond
    ;; Handle [:env "VAR_NAME"] pattern
    (and (vector? config)
         (= 2 (count config))
         (= :env (first config))
         (string? (second config)))
    (get-env (second config))

    ;; Recursively process maps
    (map? config)
    (reduce-kv (fn [m k v]
                 (assoc m k (resolve-env-refs v)))
               {}
               config)

    ;; Recursively process sequential collections
    (sequential? config)
    (mapv resolve-env-refs config)

    ;; Return other values as-is
    :else config))

(defn- ensure-api-key
  "Ensures API key is present, getting from environment if needed"
  [config provider]
  (if (:api-key config)
    config
    (let [env-key-mapping {:openai "OPENAI_API_KEY"
                           :google "GEMINI_API_KEY"
                           :anthropic "ANTHROPIC_API_KEY"}
          env-key (get env-key-mapping provider)]
      (if-let [api-key (and env-key (get-env env-key))]
        (assoc config :api-key api-key)
        config))))

(defn- ensure-provider
  "Ensures provider is present in config, extracting from model-key if needed.
   If config already has :provider, that takes precedence."
  [config model-key]
  (if (:provider config)
    config
    (assoc config :provider (get-provider model-key))))

;; Multimethod for creating model builders based on provider
(defmulti create-builder
  "Creates a model builder for the given config.
   Returns the builder object without calling .build()
   
   Dispatches on :provider in config (:anthropic, :google, :openai, etc.)"
  (fn [config] (:provider config)))

;; Helper function for applying common parameters
(defn- apply-common-params [builder config]
  (cond-> builder
    (:api-key config) (.apiKey (:api-key config))
    (:base-url config) (.baseUrl (:base-url config))
    (:temperature config) (.temperature (double (:temperature config)))
    (:top-p config) (.topP (double (:top-p config)))
    ;; Note: max-tokens handled separately per provider
    ;; Anthropic/OpenAI use maxTokens, Gemini uses maxOutputTokens
    (:stop-sequences config) (.stopSequences (:stop-sequences config))
    (:max-retries config) (.maxRetries (int (:max-retries config)))
    (:log-requests config) (.logRequests (:log-requests config))
    (:log-responses config) (.logResponses (:log-responses config))))

;; Anthropic implementation
(defmethod create-builder :anthropic
  [config]
  (let [builder (AnthropicChatModel/builder)]
    (-> builder
        (apply-common-params config)
        (cond->
         (:model-name config) (.modelName (:model-name config))
         (:max-tokens config) (.maxTokens (int (:max-tokens config)))
         (:top-k config) (.topK (int (:top-k config)))
          ;; Thinking configuration
         (get-in config [:thinking :enabled]) (.thinkingType "enabled")
         (get-in config [:thinking :return]) (.returnThinking true)
         (get-in config [:thinking :send]) (.sendThinking true)
         (get-in config [:thinking :budget-tokens])
         (.thinkingBudgetTokens (int (get-in config [:thinking :budget-tokens])))
          ;; Anthropic-specific
         (get-in config [:anthropic :version]) (.version (get-in config [:anthropic :version]))
         (get-in config [:anthropic :beta]) (.beta (get-in config [:anthropic :beta]))
         (get-in config [:anthropic :cache-system-messages])
         (.cacheSystemMessages (get-in config [:anthropic :cache-system-messages]))
         (get-in config [:anthropic :cache-tools])
         (.cacheTools (get-in config [:anthropic :cache-tools]))))))

;; Google Gemini implementation
(defn- build-thinking-config [thinking-params]
  (when thinking-params
    (let [effort (:effort thinking-params :medium)
          budget (or (:budget-tokens thinking-params)
                     (get {:low 1024 :medium 4096 :high 8192} effort))]
      (-> (GeminiThinkingConfig/builder)
          (.includeThoughts true)
          (.thinkingBudget (int budget))
          (.build)))))

(defmethod create-builder :google
  [config]
  (let [builder (GoogleAiGeminiChatModel/builder)]
    (-> builder
        (apply-common-params config)
        (cond->
         (:model-name config) (.modelName (:model-name config))
         (:max-tokens config) (.maxOutputTokens (int (:max-tokens config)))
         (:top-k config) (.topK (int (:top-k config)))
         (:seed config) (.seed (int (:seed config)))
         (:frequency-penalty config) (.frequencyPenalty (double (:frequency-penalty config)))
         (:presence-penalty config) (.presencePenalty (double (:presence-penalty config)))
          ;; Thinking configuration
         (get-in config [:thinking :enabled])
         (.thinkingConfig (build-thinking-config (:thinking config)))
         (get-in config [:thinking :return]) (.returnThinking true)
         (get-in config [:thinking :send]) (.sendThinking true)
          ;; Gemini-specific
         (get-in config [:gemini :allow-code-execution])
         (.allowCodeExecution (get-in config [:gemini :allow-code-execution]))
         (get-in config [:gemini :include-code-execution-output])
         (.includeCodeExecutionOutput (get-in config [:gemini :include-code-execution-output]))
         (get-in config [:gemini :response-logprobs])
         (.responseLogprobs (get-in config [:gemini :response-logprobs]))
         (get-in config [:gemini :enable-enhanced-civic-answers])
         (.enableEnhancedCivicAnswers (get-in config [:gemini :enable-enhanced-civic-answers]))
         (get-in config [:gemini :logprobs])
         (.logprobs (int (get-in config [:gemini :logprobs])))))))

;; OpenAI implementation
(defn- apply-openai-reasoning-params [builder config]
  (if-let [effort (get-in config [:thinking :effort])]
    (let [params-builder (OpenAiChatRequestParameters/builder)]
      (.defaultRequestParameters
       builder
       (-> params-builder
           (.reasoningEffort (name effort))
           (.build))))
    builder))

(defmethod create-builder :openai
  [config]
  (let [builder (OpenAiChatModel/builder)]
    (-> builder
        (apply-common-params config)
        (cond->
         (:model-name config) (.modelName (:model-name config))
         (:max-tokens config) (.maxTokens (int (:max-tokens config)))
         (:seed config) (.seed (int (:seed config)))
         (:frequency-penalty config) (.frequencyPenalty (double (:frequency-penalty config)))
         (:presence-penalty config) (.presencePenalty (double (:presence-penalty config)))
          ;; Thinking configuration (for reasoning models)
         (get-in config [:thinking :effort]) (apply-openai-reasoning-params config)
         (get-in config [:thinking :return]) (.returnThinking true)
          ;; OpenAI-specific
         (get-in config [:openai :organization-id])
         (.organizationId (get-in config [:openai :organization-id]))
         (get-in config [:openai :project-id])
         (.projectId (get-in config [:openai :project-id]))
         (get-in config [:openai :max-completion-tokens])
         (.maxCompletionTokens (int (get-in config [:openai :max-completion-tokens])))
         (get-in config [:openai :logit-bias])
         (.logitBias (get-in config [:openai :logit-bias]))
         (get-in config [:openai :strict-json-schema])
         (.strictJsonSchema (get-in config [:openai :strict-json-schema]))
         (get-in config [:openai :user])
         (.user (get-in config [:openai :user]))
         (get-in config [:openai :strict-tools])
         (.strictTools (get-in config [:openai :strict-tools]))
         (get-in config [:openai :parallel-tool-calls])
         (.parallelToolCalls (get-in config [:openai :parallel-tool-calls]))
         (get-in config [:openai :store])
         (.store (get-in config [:openai :store]))
         (get-in config [:openai :metadata])
         (.metadata (get-in config [:openai :metadata]))
         (get-in config [:openai :service-tier])
         (.serviceTier (get-in config [:openai :service-tier]))))))

;; Default implementation for unknown providers
(defmethod create-builder :default
  [config]
  (throw (ex-info "Unknown provider" {:provider (:provider config)})))

;; Public API functions

(defn create-model-builder
  "Creates a model builder from a configuration map.
   
   Usage:
   (create-model-builder :openai/o4-mini {:api-key \"...\"})
   (create-model-builder :anthropic/claude-sonnet-4-reasoning 
                         {:api-key \"...\" 
                          :thinking {:effort :high}})
   
   Options:
   - :validate? - When true, validates config against specs (default: true)
   
   Returns a builder object. Call .build() on it to get the final model."
  ([model-key config-overrides]
   (create-model-builder model-key config-overrides {:validate? true}))
  ([model-key config-overrides {:keys [validate?] :or {validate? true}}]
   (let [;; Resolve env refs in config overrides
         resolved-overrides (resolve-env-refs config-overrides)
         base-config (merge-with-defaults model-key resolved-overrides)
         config (-> base-config
                    (resolve-env-refs) ; Also resolve any env refs from defaults
                    (ensure-provider model-key)
                    (as-> cfg (ensure-api-key cfg (:provider cfg))))]
     ;; Validate if requested
     (when validate?
       (schema/validate-model-key model-key)
       (schema/validate-config-for-provider config))
     (create-builder config))))

(defn create-builder-from-config
  "Creates a model builder from a complete configuration map without defaults.
   Provider must be specified either as first arg or in config's :provider key.
   
   Options:
   - :validate? - When true, validates config against specs (default: true)
   
   Returns a builder object. Call .build() on it to get the final model."
  ([provider config]
   (create-builder-from-config provider config {:validate? true}))
  ([provider config {:keys [validate?] :or {validate? true}}]
   (let [;; Resolve env refs in config
         resolved-config (resolve-env-refs config)
         ;; Use provider from config if present, otherwise use arg
         final-provider (or (:provider resolved-config) provider)
         final-config (-> resolved-config
                          (assoc :provider final-provider)
                          (ensure-api-key final-provider))]
     ;; Validate if requested
     (when validate?
       (schema/validate-config-for-provider final-config))
     (create-builder final-config))))

(defn build-model
  "Convenience function that creates a model builder and builds it immediately.
   
   Usage:
   (build-model :openai/o4-mini {:temperature 0.5})
   (build-model :openai/o4-mini {:temperature 0.5} {:validate? false})
   
   Returns a fully built model ready for use."
  ([model-key config-overrides]
   (.build (create-model-builder model-key config-overrides)))
  ([model-key config-overrides options]
   (.build (create-model-builder model-key config-overrides options))))

(defn build-model-from-config
  "Convenience function that creates and builds a model from config.
   
   Returns a fully built model ready for use."
  ([provider config]
   (.build (create-builder-from-config provider config)))
  ([provider config options]
   (.build (create-builder-from-config provider config options))))

(defn available-models
  "Returns a list of available model keys with default configurations."
  []
  (keys default-configs))

(defn extend-provider
  "Helper function to extend the multimethod for custom providers.
   
   Usage:
   (extend-provider :my-provider 
     (fn [config]
       (-> (MyProviderModel/builder)
           (apply-common-params config)
           ...)))"
  [provider builder-fn]
  (defmethod create-builder provider
    [c]
    (builder-fn c)))

(defn create-model-builder-from-config
  "Creates a model builder using configuration from nrepl-client-map.
   Checks user-configured models first, then falls back to defaults.
   
   Usage:
   (create-model-builder-from-config nrepl-client-map :openai/my-custom-gpt4)
   (create-model-builder-from-config nrepl-client-map :openai/gpt-4o {:temperature 0.5})
   
   Options:
   - :validate? - When true, validates config against specs (default: true)
   
   Returns a builder object. Call .build() on it to get the final model."
  ([nrepl-client-map model-key]
   (create-model-builder-from-config nrepl-client-map model-key {} {}))
  ([nrepl-client-map model-key config-overrides]
   (create-model-builder-from-config nrepl-client-map model-key config-overrides {:validate? true}))
  ([nrepl-client-map model-key config-overrides {:keys [validate?] :or {validate? true} :as options}]
   (let [user-models (config/get-models nrepl-client-map)
         ;; Look in user config first, then defaults
         base-config (or (get user-models model-key)
                         (get default-configs model-key))
         _ (when (nil? base-config)
             (throw (ex-info (str "Unknown model key: " model-key
                                  ". Not found in user config or defaults.")
                             {:model-key model-key
                              :available-user-models (keys user-models)
                              :available-default-models (keys default-configs)})))
         ;; Resolve env refs in base config and overrides
         resolved-base (resolve-env-refs base-config)
         resolved-overrides (resolve-env-refs config-overrides)
         ;; Merge resolved configs
         merged-config (merge resolved-base resolved-overrides)
         ;; Ensure provider is set (from config or model-key)
         config-with-provider (ensure-provider merged-config model-key)
         ;; Extract provider for API key
         provider (:provider config-with-provider)
         ;; Ensure API key
         final-config (ensure-api-key config-with-provider provider)]
     ;; Validate if requested
     (when validate?
       (schema/validate-model-key model-key)
       (schema/validate-config-for-provider final-config))
     ;; Create builder
     (create-builder final-config))))

(defn create-model-from-config
  "Convenience function that creates and builds a model using configuration from nrepl-client-map.
   This is equivalent to calling (.build (create-model-builder-from-config ...))
   
   Usage:
   (create-model-from-config nrepl-client-map :openai/my-custom-gpt4)
   (create-model-from-config nrepl-client-map :openai/gpt-4o {:temperature 0.5})
   
   Options:
   - :validate? - When true, validates config against specs (default: true)
   
   Returns a fully built model ready for use."
  ([nrepl-client-map model-key]
   (create-model-from-config nrepl-client-map model-key {} {}))
  ([nrepl-client-map model-key config-overrides]
   (create-model-from-config nrepl-client-map model-key config-overrides {:validate? true}))
  ([nrepl-client-map model-key config-overrides options]
   (.build (create-model-builder-from-config nrepl-client-map model-key config-overrides options))))

(defn get-tool-model
  "Gets and creates a model for a specific tool based on its configuration.
   Looks for the model configuration key within the tool's config and creates the model.
   
   Args:
   - nrepl-client-map: The nREPL client map containing configuration
   - tool-key: The tool identifier (e.g., :dispatch_agent)
   - model-config-key: The key within tool config that contains the model reference (default: :model)
   
   Returns the created model or nil if not configured or creation fails.
   
   Example:
   (get-tool-model nrepl-client-map :dispatch_agent :model)
   ;; Looks for :tools-config {:dispatch_agent {:model :openai/o3}}
   ;; Then creates model from :models {:openai/o3 {...}}"
  ([nrepl-client-map tool-key]
   (get-tool-model nrepl-client-map tool-key :model))
  ([nrepl-client-map tool-key model-config-key]
   (when-let [tool-config (config/get-tool-config nrepl-client-map tool-key)]
     (when-let [model-key (get tool-config model-config-key)]
       (try
         (log/info (str "Creating model for " tool-key " from config: " model-key))
         (create-model-from-config nrepl-client-map model-key)
         (catch Exception e
           (log/warn e (str "Failed to create model from config for " tool-key ": " model-key))
           nil))))))
