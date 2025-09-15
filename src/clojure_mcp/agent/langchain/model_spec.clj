(ns clojure-mcp.agent.langchain.model-spec
  "Specifications for model configuration validation"
  (:require
   [clojure.spec.alpha :as s]))

;; ============================================================================
;; Common parameter specs
;; ============================================================================

(s/def ::api-key string?)
(s/def ::base-url string?)
(s/def ::model-name (s/or :string string?
                          :enum-value #(instance? Enum %)))

;; Numeric parameters with reasonable bounds
(s/def ::temperature (s/and number? #(<= 0 % 2)))
(s/def ::top-p (s/and number? #(<= 0 % 1)))
(s/def ::top-k (s/and pos-int? #(<= % 1000)))
(s/def ::max-tokens (s/and pos-int? #(<= % 100000)))
(s/def ::seed int?)
(s/def ::frequency-penalty (s/and number? #(<= -2 % 2)))
(s/def ::presence-penalty (s/and number? #(<= -2 % 2)))
(s/def ::max-retries (s/and nat-int? #(<= % 10)))
(s/def ::timeout (s/and pos-int? #(<= % 600000))) ; Max 10 minutes

;; Boolean flags
(s/def ::log-requests boolean?)
(s/def ::log-responses boolean?)

;; Collections
(s/def ::stop-sequences (s/coll-of string? :kind sequential?))

;; ============================================================================
;; Thinking/Reasoning configuration
;; ============================================================================

(s/def ::effort #{:low :medium :high})
(s/def ::enabled boolean?)
(s/def ::return boolean?)
(s/def ::send boolean?)
(s/def ::budget-tokens (s/and pos-int? #(<= % 100000)))

(s/def ::thinking
  (s/keys :opt-un [::effort ::enabled ::return ::send ::budget-tokens]))

;; ============================================================================
;; Provider-specific configurations
;; ============================================================================

;; Anthropic-specific
(s/def ::version string?)
(s/def ::beta (s/nilable string?))
(s/def ::cache-system-messages boolean?)
(s/def ::cache-tools boolean?)

(s/def ::anthropic
  (s/keys :opt-un [::version ::beta ::cache-system-messages ::cache-tools]))

;; Google-specific
(s/def ::allow-code-execution boolean?)
(s/def ::include-code-execution-output boolean?)
(s/def ::response-logprobs boolean?)
(s/def ::enable-enhanced-civic-answers boolean?)
(s/def ::logprobs (s/and nat-int? #(<= % 10)))
(s/def ::safety-settings map?) ; Could be more specific

(s/def ::google
  (s/keys :opt-un [::allow-code-execution
                   ::include-code-execution-output
                   ::response-logprobs
                   ::enable-enhanced-civic-answers
                   ::logprobs
                   ::safety-settings]))

;; OpenAI-specific
(s/def ::organization-id string?)
(s/def ::project-id string?)
(s/def ::max-completion-tokens (s/and pos-int? #(<= % 100000)))
(s/def ::logit-bias (s/map-of string? int?))
(s/def ::strict-json-schema boolean?)
(s/def ::user string?)
(s/def ::strict-tools boolean?)
(s/def ::parallel-tool-calls boolean?)
(s/def ::store boolean?)
(s/def ::metadata (s/map-of string? string?))
(s/def ::service-tier string?)

(s/def ::openai
  (s/keys :opt-un [::organization-id
                   ::project-id
                   ::max-completion-tokens
                   ::logit-bias
                   ::strict-json-schema
                   ::user
                   ::strict-tools
                   ::parallel-tool-calls
                   ::store
                   ::metadata
                   ::service-tier]))

;; ============================================================================
;; Response format configuration
;; ============================================================================

(s/def ::type #{:json :text})
(s/def ::schema map?) ; JSON schema

(s/def ::response-format
  (s/keys :req-un [::type]
          :opt-un [::schema]))

;; ============================================================================
;; Main configuration specs
;; ============================================================================

(s/def ::model-config
  (s/keys :opt-un [;; Common parameters
                   ::api-key
                   ::base-url
                   ::model-name
                   ::provider
                   ::temperature
                   ::top-p
                   ::top-k
                   ::max-tokens
                   ::seed
                   ::frequency-penalty
                   ::presence-penalty
                   ::max-retries
                   ::timeout
                   ::log-requests
                   ::log-responses
                   ::stop-sequences

                   ;; Complex configurations
                   ::thinking
                   ::response-format

                   ;; Provider-specific
                   ::anthropic
                   ::google
                   ::openai]))

;; ============================================================================
;; Provider-specific model config specs
;; ============================================================================

(s/def ::anthropic-config
  (s/merge ::model-config
           (s/keys :opt-un [::top-k]))) ; top-k is Anthropic & Google only

(s/def ::google-config
  (s/merge ::model-config
           (s/keys :opt-un [::top-k ::seed ::frequency-penalty ::presence-penalty])))

(s/def ::openai-config
  (s/merge ::model-config
           (s/keys :opt-un [::seed ::frequency-penalty ::presence-penalty])))

;; ============================================================================
;; Validation functions
;; ============================================================================

(defn validate-config
  "Validates a model configuration map against the spec.
   Returns the config if valid, throws ex-info with explain-data if not."
  [config]
  (if (s/valid? ::model-config config)
    config
    (throw (ex-info "Invalid model configuration"
                    {:explain-data (s/explain-data ::model-config config)
                     :config config}))))

(defn validate-config-for-provider
  "Validates a model configuration for its specified provider.
   Provider is extracted from the config's :provider key."
  [config]
  (let [provider (:provider config)
        spec (case provider
               :anthropic ::anthropic-config
               :google ::google-config
               :openai ::openai-config
               ::model-config)]
    (if (s/valid? spec config)
      config
      (throw (ex-info (str "Invalid configuration for provider " provider)
                      {:provider provider
                       :explain-data (s/explain-data spec config)
                       :config config})))))

(defn explain-config
  "Returns a human-readable explanation of why a config is invalid.
   Returns nil if the config is valid."
  [config]
  (when-not (s/valid? ::model-config config)
    (s/explain-str ::model-config config)))

(defn explain-config-for-provider
  "Returns a human-readable explanation for provider-specific config validation.
   Provider is extracted from the config's :provider key."
  [config]
  (let [provider (:provider config)
        spec (case provider
               :anthropic ::anthropic-config
               :google ::google-config
               :openai ::openai-config
               ::model-config)]
    (when-not (s/valid? spec config)
      (s/explain-str spec config))))

;; ============================================================================
;; Conforming and coercion
;; ============================================================================

(defn conform-config
  "Attempts to conform a config to the spec, coercing values where appropriate."
  [config]
  (let [conformed (s/conform ::model-config config)]
    (if (= conformed ::s/invalid)
      (throw (ex-info "Cannot conform configuration"
                      {:explain-data (s/explain-data ::model-config config)
                       :config config}))
      conformed)))

(defn coerce-numeric-params
  "Coerces string numeric parameters to appropriate numeric types."
  [config]
  (cond-> config
    (string? (:temperature config))
    (update :temperature #(Double/parseDouble %))

    (string? (:top-p config))
    (update :top-p #(Double/parseDouble %))

    (string? (:top-k config))
    (update :top-k #(Integer/parseInt %))

    (string? (:max-tokens config))
    (update :max-tokens #(Integer/parseInt %))

    (string? (:timeout config))
    (update :timeout #(Integer/parseInt %))

    (string? (:max-retries config))
    (update :max-retries #(Integer/parseInt %))

    (string? (:seed config))
    (update :seed #(Integer/parseInt %))

    (string? (:frequency-penalty config))
    (update :frequency-penalty #(Double/parseDouble %))

    (string? (:presence-penalty config))
    (update :presence-penalty #(Double/parseDouble %))))

;; ============================================================================
;; Model key validation
;; ============================================================================

(def valid-providers #{:anthropic :google :openai})

(s/def ::provider valid-providers)
(s/def ::model-key (s/and keyword?
                          namespace)) ; Just require it has a namespace

(defn validate-model-key
  "Validates that a model key has a namespace."
  [model-key]
  (if (s/valid? ::model-key model-key)
    model-key
    (throw (ex-info "Invalid model key - must have a namespace"
                    {:model-key model-key
                     :explain (s/explain-str ::model-key model-key)}))))

;; ============================================================================
;; Generator functions for testing
;; ============================================================================

(defn gen-valid-config
  "Generates a valid random configuration for testing.
   Requires clojure.spec.gen.alpha to be available."
  []
  (try
    (require '[clojure.spec.gen.alpha :as gen])
    ((resolve 'gen/generate) (s/gen ::model-config))
    (catch Exception _
      (throw (ex-info "spec.gen not available - add test.check to dependencies" {})))))
