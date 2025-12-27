(ns clojure-mcp.agent.langchain.model
  "Bridge between clojure-mcp config and langchain4clj models."
  (:require
   [langchain4clj.core :as lc]
   [langchain4clj.presets :as presets]
   [clojure-mcp.config :as config]
   [taoensso.timbre :as log]))

;; Provider mapping

(def ^:private provider-mapping
  {:google :google-ai-gemini
   :openai :openai
   :anthropic :anthropic
   :ollama :ollama
   :mistral :mistral
   :vertex-ai :vertex-ai-gemini})

(defn- normalize-provider [provider]
  (get provider-mapping provider provider))

(defn- coerce-integers
  "Converts Long values to Integer for LangChain4j compatibility."
  [config]
  (let [int-keys [:max-tokens :max-retries :timeout :top-k :seed]]
    (reduce (fn [m k]
              (if-let [v (get m k)]
                (if (instance? Long v)
                  (assoc m k (int v))
                  m)
                m))
            config
            int-keys)))

(defn- normalize-config [config]
  (-> config
      (cond-> (:provider config) (update :provider normalize-provider))
      (cond-> (and (:model-name config) (not (:model config)))
        (-> (assoc :model (:model-name config))
            (dissoc :model-name)))
      (coerce-integers)))

;; Public API

(defn get-provider [model-key]
  (-> model-key namespace keyword))

(defn available-models []
  (presets/available-presets))

(defn create-model-from-config
  "Creates a chat model from user config or langchain4clj presets."
  ([nrepl-client-map model-key]
   (create-model-from-config nrepl-client-map model-key {}))
  ([nrepl-client-map model-key config-overrides]
   (let [user-models (config/get-models nrepl-client-map)
         base-config (or (get user-models model-key)
                         (try
                           (presets/get-preset model-key)
                           (catch Exception _
                             nil)))
         _ (when (nil? base-config)
             (throw (ex-info (str "Unknown model key: " model-key)
                             {:model-key model-key
                              :available-user-models (keys user-models)
                              :available-presets (presets/available-presets)})))
         merged-config (merge base-config config-overrides)
         config-with-provider (if (:provider merged-config)
                                merged-config
                                (assoc merged-config :provider (get-provider model-key)))
         final-config (normalize-config config-with-provider)]
     (log/debug "Creating model" model-key "with config:" (dissoc final-config :api-key))
     (lc/create-model final-config))))

(defn get-tool-model
  "Gets and creates a model for a specific tool based on its configuration."
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

;; Backward compatibility

(defn build-model
  "DEPRECATED: Use create-model-from-config instead."
  ([model-key config-overrides]
   (let [preset (presets/get-preset model-key config-overrides)]
     (lc/create-model (normalize-config preset))))
  ([model-key]
   (build-model model-key {})))
