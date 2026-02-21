(ns clojure-mcp.agent.langchain
  "Adapter layer between clojure-mcp and langchain4clj."
  (:require
   [langchain4clj.core :as lc]
   [langchain4clj.presets :as presets]
   [langchain4clj.assistant :as assistant]
   [langchain4clj.messages :as messages]
   [langchain4clj.tools.helpers :as tool-helpers]
   [clojure.data.json :as json]
   [clojure.string :as string]))

(def default-max-memory 100)

;; Memory functions

(defn chat-memory
  ([]
   (chat-memory default-max-memory))
  ([size]
   (assistant/create-memory {:max-messages size})))

(defn memory-add! [memory message]
  (let [msg (if (map? message)
              (messages/edn->message message)
              message)]
    (assistant/add-message! memory msg)))

(defn memory-messages [memory]
  (vec (assistant/get-messages memory)))

(defn memory-clear! [memory]
  (assistant/clear! memory))

(defn memory-count [memory]
  (count (assistant/get-messages memory)))

;; Model creation

(defn- detect-available-provider []
  (cond
    (System/getenv "ANTHROPIC_API_KEY") :anthropic
    (System/getenv "GEMINI_API_KEY") :google-ai-gemini
    (System/getenv "OPENAI_API_KEY") :openai
    :else nil))

(defn- default-model-for-provider [provider reasoning?]
  (case provider
    :anthropic (if reasoning?
                 :anthropic/claude-sonnet-4-reasoning
                 :anthropic/claude-sonnet-4)
    :google-ai-gemini (if reasoning?
                        :google/gemini-2-5-flash-reasoning
                        :google/gemini-2-5-flash)
    :openai (if reasoning?
              :openai/o4-mini-reasoning
              :openai/o4-mini)
    nil))

(defn agent-model []
  (when-let [provider (detect-available-provider)]
    (when-let [model-key (default-model-for-provider provider false)]
      (lc/create-model (presets/get-preset model-key)))))

(defn reasoning-agent-model []
  (when-let [provider (detect-available-provider)]
    (when-let [model-key (default-model-for-provider provider true)]
      (lc/create-model (presets/get-preset model-key)))))

;; Tool conversion

(defn- adapt-tool-fn
  "Adapts clojure-mcp callback-style tool to langchain4clj return style.
   Includes a 5-minute timeout to prevent indefinite blocking."
  [callback-style-fn]
  (fn [args]
    (let [result-promise (promise)]
      (callback-style-fn
       nil
       args
       (fn [result error]
         (deliver result-promise
                  (if error
                    (str "Tool Error: " (string/join "\n" (if (sequential? result) result [result])))
                    (if (sequential? result)
                      (string/join "\n\n" result)
                      (str result))))))
      (let [result (deref result-promise 300000 ::timeout)]
        (if (= result ::timeout)
          "Tool Error: Execution timed out after 5 minutes"
          result)))))

(defn- registration-map->tool-map
  "Converts clojure-mcp tool registration map to langchain4clj tool map format."
  [{:keys [name description schema tool-fn]}]
  {:pre [(string? name) (string? description)
         (or (string? schema) (map? schema))]}
  (let [params (if (string? schema)
                 (json/read-str schema :key-fn keyword)
                 schema)
        spec (tool-helpers/create-tool-spec
              {:name name
               :description description
               :parameters params})
        executor-fn (adapt-tool-fn tool-fn)]
    {:name name
     :description description
     :specification spec
     :executor-fn executor-fn}))

(defn convert-tools
  "Converts clojure-mcp tool registration maps to langchain4clj tool format."
  [registration-maps]
  (mapv registration-map->tool-map registration-maps))

(defn convert-tools-for-aiservices
  "Converts clojure-mcp tool registration maps to AiServices format."
  [registration-maps]
  (into {}
        (map (fn [{:keys [name description schema tool-fn]}]
               (let [spec (tool-helpers/create-tool-spec
                           {:name name
                            :description description
                            :parameters (if (string? schema)
                                          (json/read-str schema :key-fn keyword)
                                          schema)})
                     executor (tool-helpers/create-tool-executor
                               (adapt-tool-fn tool-fn)
                               {:parse-args? true})]
                 [spec executor]))
             registration-maps)))

;; Assistant creation

(defn create-assistant-fn
  "Creates an assistant function with the given configuration.
   
   Options:
   - :model - The LLM model to use
   - :memory - Chat memory instance
   - :tools - Sequence of tool registration maps
   - :system-message - System message for the assistant
   - :max-iterations - Maximum tool iterations (default: 10)"
  [{:keys [model memory tools system-message max-iterations]
    :or {max-iterations 10}}]
  (assistant/create-assistant
   {:model model
    :memory memory
    :tools (when (seq tools) (convert-tools tools))
    :system-message system-message
    :max-iterations max-iterations}))

;; Message helpers

(defn user-message [content]
  (messages/edn->message {:type :user :text (if (string? content) content (str content))}))

(defn system-message [text]
  (messages/edn->message {:type :system :text text}))


