(ns clojure-mcp.config.schema
  "Malli schemas for configuration validation.
   
   Provides comprehensive validation for the .clojure-mcp/config.edn file
   with human-readable error messages and spell-checking for typos."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ==============================================================================
;; Basic Type Schemas
;; ==============================================================================

(def Path
  "Schema for file system paths"
  :string)

(def EnvRef
  "Schema for environment variable references like [:env \"VAR_NAME\"]"
  [:tuple {:description "Environment variable reference"}
   [:= :env]
   :string])

;; ==============================================================================
;; Model Configuration Schemas
;; ==============================================================================

(def ThinkingConfig
  "Schema for thinking/reasoning configuration in models"
  [:map {:closed true
         :error/message "Invalid thinking configuration. See model documentation."}
   [:enabled {:optional true} :boolean]
   [:return {:optional true} :boolean]
   [:send {:optional true} :boolean]
   [:effort {:optional true} [:enum :low :medium :high]]
   [:budget-tokens {:optional true} [:int {:min 1 :max 100000}]]])

(def ModelConfig
  "Schema for individual model configurations"
  [:map {:closed true
         :error/message "Model config must have :model-name. See doc/model-configuration.md"}
   ;; Provider identification
   [:provider {:optional true} [:enum :openai :anthropic :google]]

   ;; Core parameters  
   [:model-name [:or :string EnvRef]]
   [:api-key {:optional true} [:or :string EnvRef]]
   [:base-url {:optional true} [:or :string EnvRef]]

   ;; Common generation parameters
   [:temperature {:optional true} [:and :double [:>= 0] [:<= 2]]]
   [:max-tokens {:optional true} [:int {:min 1 :max 100000}]]
   [:top-p {:optional true} [:and :double [:>= 0] [:<= 1]]]
   [:top-k {:optional true} [:int {:min 1 :max 1000}]]
   [:seed {:optional true} :int]
   [:frequency-penalty {:optional true} [:and :double [:>= -2] [:<= 2]]]
   [:presence-penalty {:optional true} [:and :double [:>= -2] [:<= 2]]]
   [:stop-sequences {:optional true} [:sequential :string]]

   ;; Connection and logging parameters
   [:max-retries {:optional true} [:int {:min 0 :max 10}]]
   [:timeout {:optional true} [:int {:min 1000 :max 600000}]] ; 1 sec to 10 min
   [:log-requests {:optional true} :boolean]
   [:log-responses {:optional true} :boolean]

   ;; Thinking/reasoning configuration
   [:thinking {:optional true} ThinkingConfig]

   ;; Response format configuration
   [:response-format {:optional true}
    [:map {:closed true}
     [:type [:enum :json :text]]
     [:schema {:optional true} :map]]]

   ;; Provider-specific: Anthropic
   [:anthropic {:optional true}
    [:map {:closed true}
     [:version {:optional true} :string]
     [:beta {:optional true} [:maybe :string]]
     [:cache-system-messages {:optional true} :boolean]
     [:cache-tools {:optional true} :boolean]]]

   ;; Provider-specific: Google Gemini
   [:google {:optional true}
    [:map {:closed true}
     [:allow-code-execution {:optional true} :boolean]
     [:include-code-execution-output {:optional true} :boolean]
     [:response-logprobs {:optional true} :boolean]
     [:enable-enhanced-civic-answers {:optional true} :boolean]
     [:logprobs {:optional true} [:int {:min 0 :max 10}]]
     [:safety-settings {:optional true} :map]]]

   ;; Provider-specific: OpenAI
   [:openai {:optional true}
    [:map {:closed true}
     [:organization-id {:optional true} :string]
     [:project-id {:optional true} :string]
     [:max-completion-tokens {:optional true} [:int {:min 1 :max 100000}]]
     [:logit-bias {:optional true} [:map-of :string :int]]
     [:strict-json-schema {:optional true} :boolean]
     [:user {:optional true} :string]
     [:strict-tools {:optional true} :boolean]
     [:parallel-tool-calls {:optional true} :boolean]
     [:store {:optional true} :boolean]
     [:metadata {:optional true} [:map-of :string :string]]
     [:service-tier {:optional true} :string]]]

   ;; Provider-specific: Gemini (alternate naming for Google)
   [:gemini {:optional true}
    [:map {:closed true}
     [:allow-code-execution {:optional true} :boolean]
     [:include-code-execution-output {:optional true} :boolean]
     [:response-logprobs {:optional true} :boolean]
     [:enable-enhanced-civic-answers {:optional true} :boolean]
     [:logprobs {:optional true} [:int {:min 0 :max 10}]]]]])

;; ==============================================================================
;; Agent Configuration Schemas  
;; ==============================================================================

(def AgentConfig
  "Schema for agent configurations"
  [:map {:closed true
         :error/message "Agent config requires :id, :name, and :description. See README Agent Tools section"}
   [:id :keyword]
   [:name :string]
   [:description :string]
   [:model {:optional true} :keyword]
   [:system-prompt {:optional true} :string]
   [:system-message {:optional true} :string] ;; Alternative to system-prompt
   [:tools {:optional true} [:sequential :keyword]]
   [:context {:optional true}
    [:or :boolean [:sequential :string]]] ;; Can be true, false, or list of files
   [:enable-tools {:optional true}
    [:or [:= :all] [:sequential :keyword]]] ;; Can be :all or list of tools
   [:disable-tools {:optional true}
    [:maybe [:sequential :keyword]]]])

;; ==============================================================================
;; Resource Configuration Schemas
;; ==============================================================================

(def ResourceEntry
  "Schema for resource entries"
  [:map {:closed true
         :error/message "Resource entries must have :description and :file-path. See doc/configuring-resources.md"}
   [:description :string]
   [:file-path Path]
   [:url {:optional true} [:maybe :string]]
   [:mime-type {:optional true} [:maybe :string]]])

;; ==============================================================================
;; Prompt Configuration Schemas
;; ==============================================================================

(def PromptArg
  "Schema for prompt arguments"
  [:map {:closed true}
   [:name :string]
   [:description :string]
   [:required? {:optional true} :boolean]])

(def PromptEntry
  "Schema for prompt entries"
  [:map {:closed true
         :error/message "Prompt entries must have :description. See doc/configuring-prompts.md"}
   [:description :string]
   [:content {:optional true}
    [:or
     :string
     [:map {:closed true} [:file-path Path]]]]
   [:file-path {:optional true} Path] ;; Alternative to :content
   [:args {:optional true} [:sequential PromptArg]]])

;; ==============================================================================
;; Main Configuration Schema
;; ==============================================================================

(def Config
  "Complete configuration schema for .clojure-mcp/config.edn"
  [:map {:closed true} ;; Closed to enable spell-checking for typos

   ;; Core configuration
   [:allowed-directories {:optional true} [:sequential Path]]
   [:emacs-notify {:optional true} :boolean]
   [:write-file-guard {:optional true} [:enum :full-read :partial-read false]]
   [:cljfmt {:optional true} :boolean]
   [:bash-over-nrepl {:optional true} :boolean]
   [:nrepl-env-type {:optional true} [:enum :clj :bb :basilisp :scittle]]

   ;; Scratch pad configuration
   [:scratch-pad-load {:optional true} :boolean]
   [:scratch-pad-file {:optional true} :string]

   ;; Model and tool configuration
   [:models {:optional true} [:map-of :keyword ModelConfig]]
   [:tools-config {:optional true} [:map-of :keyword :map]]
   [:agents {:optional true} [:sequential AgentConfig]]

   ;; MCP client hints
   [:mcp-client {:optional true} [:maybe :string]]
   [:dispatch-agent-context {:optional true}
    [:or :boolean [:sequential Path]]]

   ;; Component filtering
   [:enable-tools {:optional true}
    [:maybe [:sequential [:or :keyword :string]]]]
   [:disable-tools {:optional true}
    [:maybe [:sequential [:or :keyword :string]]]]
   [:enable-prompts {:optional true}
    [:maybe [:sequential :string]]]
   [:disable-prompts {:optional true}
    [:maybe [:sequential :string]]]
   [:enable-resources {:optional true}
    [:maybe [:sequential :string]]]
   [:disable-resources {:optional true}
    [:maybe [:sequential :string]]]

   ;; Custom resources and prompts
   [:resources {:optional true} [:map-of :string ResourceEntry]]
   [:prompts {:optional true} [:map-of :string PromptEntry]]])

;; ==============================================================================
;; Validation Functions
;; ==============================================================================

(defn explain-config
  "Returns human-readable explanation of validation errors, or nil if valid.
   Automatically detects typos in configuration keys."
  [config]
  (when-not (m/validate Config config)
    (-> (m/explain Config config)
        (me/with-spell-checking)
        (me/humanize))))

(defn valid?
  "Returns true if the configuration is valid."
  [config]
  (m/validate Config config))

;; ==============================================================================
;; Schema Introspection (useful for documentation)
;; ==============================================================================

(defn schema-properties
  "Returns the properties/metadata of the Config schema.
   Useful for generating documentation."
  []
  (m/properties Config))

(defn schema-keys
  "Returns all the top-level keys defined in the Config schema."
  []
  (-> Config
      (m/entries)
      (->> (map first))))

(defn coerce-config
  "Coerces configuration values to their correct types.
   Currently a pass-through function as Malli validation doesn't auto-coerce.
   Preserves environment variable references [:env \"VAR_NAME\"] and boolean values."
  [config]
  config)
