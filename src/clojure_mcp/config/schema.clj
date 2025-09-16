(ns clojure-mcp.config.schema
  "Malli schemas for configuration validation.
   
   Provides comprehensive validation for the .clojure-mcp/config.edn file
   with human-readable error messages and spell-checking for typos."
  (:require
   [clojure.string :as string]
   [malli.core :as m]
   [malli.error :as me]))

(def ^:dynamic *validate-env-vars*
  "When true, validates that environment variables actually exist and aren't blank.
   Can be bound to false for testing."
  true)

;; ==============================================================================
;; Basic Type Schemas
;; ==============================================================================

(def NonBlankString
  [:and
   [:string {:min 1}]
   [:fn {:error/message "String can't be blank"}
    #(not (string/blank? %))]])

(def Path
  "Schema for file system paths"
  NonBlankString)

(def EnvRef
  "Schema for environment variable references like [:env \"VAR_NAME\"]"
  [:and
   [:tuple {:description "Environment variable reference"}
    [:= :env]
    NonBlankString]
   [:fn {:error/message "Environment variable can't be empty"}
    (fn [[_ env-var]]
      (or (not *validate-env-vars*)
          (let [val (System/getenv env-var)]
            (and val
                 (not (string/blank? val))))))]])

(def IntOrDouble [:or :int :double])

(def ModelNameOrEnum
  "Schema for model names - can be a string or an enum object"
  [:or
   NonBlankString
   EnvRef
   [:fn {:error/message "Must be a valid model name object"}
    #(instance? Enum %)]])

;; ==============================================================================
;; Model Configuration Schemas
;; ==============================================================================

(def ThinkingConfig
  "Schema for thinking/reasoning configuration in models"
  [:map {:closed true}
   [:enabled {:optional true} :boolean]
   [:return {:optional true} :boolean]
   [:send {:optional true} :boolean]
   [:effort {:optional true} [:enum :low :medium :high]]
   [:budget-tokens {:optional true} [:int {:min 1 :max 100000}]]])

(def ModelConfig
  "Schema for individual model configurations"
  [:map {:closed true}
   ;; Provider identification
   [:provider {:optional true} [:enum :openai :anthropic :google]]

   ;; Core parameters  
   [:model-name ModelNameOrEnum]
   [:api-key {:optional true} [:or NonBlankString EnvRef]]
   [:base-url {:optional true} [:or NonBlankString EnvRef]]

   ;; Common generation parameters
   [:temperature {:optional true} [:and IntOrDouble [:>= 0] [:<= 2]]]
   [:max-tokens {:optional true} [:int {:min 1 :max 100000}]]
   [:top-p {:optional true} [:and IntOrDouble [:>= 0] [:<= 1]]]
   [:top-k {:optional true} [:int {:min 1 :max 1000}]]
   [:seed {:optional true} :int]
   [:frequency-penalty {:optional true} [:and IntOrDouble [:>= -2] [:<= 2]]]
   [:presence-penalty {:optional true} [:and IntOrDouble [:>= -2] [:<= 2]]]
   [:stop-sequences {:optional true} [:sequential NonBlankString]]

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
     [:version {:optional true} NonBlankString]
     [:beta {:optional true} [:maybe NonBlankString]]
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
     [:organization-id {:optional true} NonBlankString]
     [:project-id {:optional true} NonBlankString]
     [:max-completion-tokens {:optional true} [:int {:min 1 :max 100000}]]
     [:logit-bias {:optional true} [:map-of NonBlankString [:int {:min -100 :max 100}]]]
     [:strict-json-schema {:optional true} :boolean]
     [:user {:optional true} NonBlankString]
     [:strict-tools {:optional true} :boolean]
     [:parallel-tool-calls {:optional true} :boolean]
     [:store {:optional true} :boolean]
     [:metadata {:optional true} [:map-of NonBlankString NonBlankString]]
     [:service-tier {:optional true} NonBlankString]]]])

;; ==============================================================================
;; Agent Configuration Schemas  
;; ==============================================================================

(def AgentConfig
  "Schema for agent configurations"
  [:map {:closed true}

   ;; Required fields
   [:id {:description "Unique keyword identifier for the agent"}
    :keyword]

   [:name {:description "Tool name that appears in the MCP interface"}
    NonBlankString]

   [:description {:description "Human-readable description of the agent's purpose"}
    NonBlankString]

   ;; System configuration
   [:system-message {:optional true
                     :description "System prompt that defines the agent's behavior and personality"}
    NonBlankString]

   ;; Model configuration
   [:model {:optional true
            :description "AI model to use (keyword reference to :models config, e.g., :openai/gpt-4o)"}
    :keyword]

   ;; Context configuration
   [:context {:optional true
              :description "Context to provide: true (default), false (none), or file paths list"}
    [:or :boolean [:sequential Path]]]

   ;; Tool configuration
   [:enable-tools {:optional true
                   :description "Tools the agent can access: :all, specific list, or nil (no tools)"}
    [:maybe [:or [:= :all] [:sequential :keyword]]]]

   [:disable-tools {:optional true
                    :description "Tools to exclude even if enabled (applied after enable-tools)"}
    [:maybe [:sequential :keyword]]]

   ;; Memory configuration
   [:memory-size {:optional true
                  :description "Memory behavior: false/nil/<10 = stateless, >=10 = persistent window"}
    [:maybe [:or [:= false] [:int {:min 0}]]]]

   ;; File tracking configuration
   [:track-file-changes {:optional true
                         :description "Whether to track and display file diffs (default: true)"}
    :boolean]])

;; ==============================================================================
;; Resource Configuration Schemas
;; ==============================================================================

(def ResourceEntry
  "Schema for resource entries"
  [:map {:closed true}

   [:description {:description "Clear description of resource contents for LLM understanding"}
    NonBlankString]

   [:file-path {:description "Path to file (relative to project root or absolute)"}
    Path]

   [:url {:optional true
          :description "Custom URL for resource (defaults to custom://kebab-case-name)"}
    [:maybe NonBlankString]]

   [:mime-type {:optional true
                :description "MIME type (auto-detected from file extension if not specified)"}
    [:maybe NonBlankString]]])

;; ==============================================================================
;; Prompt Configuration Schemas
;; ==============================================================================

(def PromptArg
  "Schema for prompt arguments"
  [:map {:closed true}

   [:name {:description "Parameter name used in Mustache template (e.g., {{name}})"}
    NonBlankString]

   [:description {:description "Description of what this parameter is for"}
    NonBlankString]

   [:required? {:optional true
                :description "Whether this argument is required (defaults to false)"}
    :boolean]])

(def PromptEntry
  "Schema for prompt entries"
  [:and
   [:map {:closed true}
    [:description {:description "Clear description of what the prompt does (shown to LLM when listing prompts)"}
     NonBlankString]

    [:content {:optional true
               :description "Inline Mustache template content (use this OR :file-path)"}
     NonBlankString]

    [:file-path {:optional true
                 :description "Path to Mustache template file (use this OR :content)"}
     Path] ;; Alternative to :content

    [:args {:optional true
            :description "Vector of argument definitions for the Mustache template"}
     [:sequential PromptArg]]]
   [:fn {:error/message "Provide exactly one of :content or :file-path"}
    (fn [{:keys [content file-path]}]
      (and (not (and (some? content) (some? file-path)))
           (or (some? content) (some? file-path))))]])

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
   [:scratch-pad-file {:optional true} Path]

   ;; Model and tool configuration
   [:models {:optional true} [:map-of :keyword ModelConfig]]
   [:tools-config {:optional true} [:map-of :keyword :map]]
   [:agents {:optional true} [:sequential AgentConfig]]

   ;; MCP client hints
   [:mcp-client {:optional true} [:maybe NonBlankString]]
   [:dispatch-agent-context {:optional true}
    [:or :boolean [:sequential Path]]]

   ;; Component filtering
   [:enable-tools {:optional true}
    [:maybe [:sequential [:or :keyword NonBlankString]]]]
   [:disable-tools {:optional true}
    [:maybe [:sequential [:or :keyword NonBlankString]]]]
   [:enable-prompts {:optional true}
    [:maybe [:sequential NonBlankString]]]
   [:disable-prompts {:optional true}
    [:maybe [:sequential NonBlankString]]]
   [:enable-resources {:optional true}
    [:maybe [:sequential NonBlankString]]]
   [:disable-resources {:optional true}
    [:maybe [:sequential NonBlankString]]]

   ;; Custom resources and prompts
   [:resources {:optional true} [:map-of NonBlankString ResourceEntry]]
   [:prompts {:optional true} [:map-of NonBlankString PromptEntry]]])

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

;; ==============================================================================
;; Model Validation Functions
;; ==============================================================================

(defn validate-model-key
  "Validates that a model key has a namespace.
   Returns the key if valid, throws an exception if invalid."
  [model-key]
  (when-not (and (keyword? model-key)
                 (namespace model-key))
    (throw (ex-info "Invalid model key"
                    {:model-key model-key
                     :type (type model-key)
                     :namespace (namespace model-key)})))
  model-key)

(defn validate-model-config
  "Validates a model configuration against the ModelConfig schema.
   Returns the config if valid, throws an exception if invalid."
  [config]
  (if (m/validate ModelConfig config)
    config
    (throw (ex-info "Invalid configuration"
                    {:config config
                     :errors (-> (m/explain ModelConfig config)
                                 (me/with-spell-checking)
                                 (me/humanize))}))))

(defn validate-config-for-provider
  "Validates a model configuration. Provider is ignored since validation
   is the same for all providers in the current schema.
   Exists for backward compatibility with model_spec.clj."
  [config]
  (validate-model-config config))
