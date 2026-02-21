(ns clojure-mcp.agent.langchain.chat-listener
  "ChatModelListener creation with backward-compatible API.
   Wraps langchain4clj.listeners with :ctx key for existing consumers."
  (:require
   [langchain4clj.listeners :as lc-listeners]))

(defn create-listener
  "Create a ChatModelListener from Clojure handler functions.
   
   Handlers receive EDN data with :ctx key for backward compatibility.
   The :ctx key contains the raw Java context object.
   
   Args:
   - handlers: Map with optional keys:
     :on-request  - (fn [request-ctx] ...) called before sending request
     :on-response - (fn [response-ctx] ...) called after receiving response
     :on-error    - (fn [error-ctx] ...) called on errors"
  [{:keys [on-request on-response on-error]}]
  (lc-listeners/create-listener
   {:on-request (when on-request
                  (fn [ctx]
                    (on-request (assoc ctx :ctx (:raw-context ctx)))))
    :on-response (when on-response
                   (fn [ctx]
                     (on-response (assoc ctx :ctx (:raw-context ctx)))))
    :on-error (when on-error
                (fn [ctx]
                  (on-error (assoc ctx :ctx (:raw-context ctx)))))}))

(defn logging-listener
  "Create a listener that logs all events."
  ([]
   (lc-listeners/logging-listener))
  ([levels]
   (lc-listeners/logging-listener levels)))

(defn token-tracking-listener
  "Create a listener that tracks token usage in an atom.
   
   NOTE: The atom structure differs from earlier versions:
   - Current: {:input-tokens N :output-tokens N :total-tokens N 
              :request-count N :last-request {...} :by-model {...}}
   - Legacy:  {:total-input-tokens N :total-output-tokens N :total-tokens N :request-count N}
   
   Consumers should update to use :input-tokens/:output-tokens keys."
  [usage-atom]
  (lc-listeners/token-tracking-listener usage-atom))

(def compose-listeners
  "Compose multiple listeners into one."
  lc-listeners/compose-listeners)
