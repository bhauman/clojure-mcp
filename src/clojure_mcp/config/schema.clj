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
  "Schema for thinking configuration in models"
  [:map {:closed true}
   [:enabled :boolean]
   [:return {:optional true} :boolean]
   [:send {:optional true} :boolean]
   [:budget-tokens {:optional true} [:int {:min 1}]]])

(def ModelConfig
  "Schema for individual model configurations"
  [:map {:closed true
         :error/message "Model config must have :model-name. See doc/model-configuration.md"}
   [:provider {:optional true} [:enum :openai :anthropic :google]]
   [:model-name [:or :string EnvRef]]
   [:temperature {:optional true} [:and :double [:>= 0] [:<= 2]]]
   [:max-tokens {:optional true} [:int {:min 1}]]
   [:api-key {:optional true} [:or :string EnvRef]]
   [:base-url {:optional true} [:or :string EnvRef]]
   [:thinking {:optional true} ThinkingConfig]])

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
