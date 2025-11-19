(ns clojure-mcp.agent.langchain.chat-listener
  "Creates ChatModelListener instances from Clojure functions that work with EDN data"
  (:require
   [clojure-mcp.agent.langchain.message-conv :as msg-conv]
   [taoensso.timbre :as log])
  (:import
   [dev.langchain4j.model.chat.listener
    ChatModelListener
    ChatModelRequestContext
    ChatModelResponseContext
    ChatModelErrorContext]
   [dev.langchain4j.model.chat.request
    ChatRequestParameters]
   [dev.langchain4j.model.chat.response
    ChatResponseMetadata]
   [dev.langchain4j.model.output
    TokenUsage]))

(defn- chat-request-parameters->edn
  "Convert ChatRequestParameters to EDN"
  [^ChatRequestParameters params]
  (when params
    {:model-name (.modelName params)
     :temperature (.temperature params)
     :top-p (.topP params)
     :top-k (.topK params)
     :frequency-penalty (.frequencyPenalty params)
     :presence-penalty (.presencePenalty params)
     :max-output-tokens (.maxOutputTokens params)
     :stop-sequences (some-> (.stopSequences params) vec)
     :tool-specifications (some-> (.toolSpecifications params) vec)
     :tool-choice (.toolChoice params)
     :response-format (.responseFormat params)}))

(defn- token-usage->edn
  "Convert TokenUsage to EDN"
  [^TokenUsage usage]
  (when usage
    {:input-token-count (.inputTokenCount usage)
     :output-token-count (.outputTokenCount usage)
     :total-token-count (.totalTokenCount usage)}))

(defn- chat-response-metadata->edn
  "Convert ChatResponseMetadata to EDN"
  [^ChatResponseMetadata metadata]
  (when metadata
    {:id (.id metadata)
     :model-name (.modelName metadata)
     :finish-reason (some-> (.finishReason metadata) str)
     :token-usage (token-usage->edn (.tokenUsage metadata))}))

(defn- chat-request-context->edn
  "Convert ChatModelRequestContext to EDN"
  [^ChatModelRequestContext ctx]
  (let [chat-request (.chatRequest ctx)]
    {:messages (-> (.messages chat-request)
                   msg-conv/messages->edn
                   msg-conv/parse-messages-tool-arguments)
     :parameters (chat-request-parameters->edn (.parameters chat-request))
     :model-provider (str (.modelProvider ctx))
     :attributes (into {} (.attributes ctx))
     :ctx ctx}))

(defn- chat-response-context->edn
  "Convert ChatModelResponseContext to EDN"
  [^ChatModelResponseContext ctx]
  (let [chat-response (.chatResponse ctx)
        chat-request (.chatRequest ctx)]
    {:ai-message (-> (.aiMessage chat-response)
                     msg-conv/message->edn
                     msg-conv/parse-tool-arguments)
     :metadata (chat-response-metadata->edn (.metadata chat-response))
     :request {:messages (msg-conv/messages->edn (.messages chat-request))
               :parameters (chat-request-parameters->edn (.parameters chat-request))}
     :model-provider (str (.modelProvider ctx))
     :attributes (into {} (.attributes ctx))
     :ctx ctx}))

(defn- chat-error-context->edn
  "Convert ChatModelErrorContext to EDN"
  [^ChatModelErrorContext ctx]
  (let [chat-request (.chatRequest ctx)]
    {:error {:message (.getMessage (.error ctx))
             :class (-> (.error ctx) class .getName)
             :stack-trace (mapv str (.getStackTrace (.error ctx)))}
     :request (when chat-request
                {:messages (msg-conv/messages->edn (.messages chat-request))
                 :parameters (chat-request-parameters->edn (.parameters chat-request))})
     :model-provider (str (.modelProvider ctx))
     :attributes (into {} (.attributes ctx))}))

(defn create-listener
  "Create a ChatModelListener from Clojure functions.
   
   Args:
   - handlers: Map with optional keys:
     :on-request - (fn [request-edn] ...) called before sending request
     :on-response - (fn [response-edn] ...) called after receiving response
     :on-error - (fn [error-edn] ...) called on errors
   
   Each handler receives EDN data converted from the Java objects.
   
   Example:
   (create-listener
     {:on-request (fn [req] (log/info \"Request:\" req))
      :on-response (fn [resp] (log/info \"Response:\" resp))
      :on-error (fn [err] (log/error \"Error:\" err))})"
  [{:keys [on-request on-response on-error]}]
  (reify ChatModelListener
    (onRequest [_ request-context]
      (when on-request
        (try
          (on-request (chat-request-context->edn request-context))
          (catch Exception _
            #_(log/error _ "Error in on-request handler")))))

    (onResponse [_ response-context]
      (when on-response
        (try
          (on-response (chat-response-context->edn response-context))
          (catch Exception _
            #_(log/error _ "Error in on-response handler")))))

    (onError [_ error-context]
      (when on-error
        (try
          (on-error (chat-error-context->edn error-context))
          (catch Exception _
            #_(log/error _ "Error in on-error handler")))))))

(defn logging-listener
  "Create a listener that logs all events at specified levels.
   
   Args (optional):
   - log-level: Map with :request, :response, :error levels
                (defaults to :info for all)
   
   Example:
   (logging-listener {:request :debug :response :info :error :error})"
  ([]
   (logging-listener {}))
  ([{:keys [request response error]
     :or {request :info response :info error :error}}]
   (create-listener
    {:on-request (fn [req]
                   (log/log request "Chat request:" req))
     :on-response (fn [resp]
                    (log/log response "Chat response:" resp))
     :on-error (fn [err]
                 (log/log error "Chat error:" err))})))

(defn token-tracking-listener
  "Create a listener that tracks token usage.
   
   Args:
   - usage-atom: Atom to accumulate token usage stats
   
   The atom will be updated with:
   {:total-input-tokens n
    :total-output-tokens n
    :total-tokens n
    :request-count n}"
  [usage-atom]
  (create-listener
   {:on-response
    (fn [resp]
      (when-let [usage (get-in resp [:metadata :token-usage])]
        (swap! usage-atom
               (fn [stats]
                 (-> stats
                     (update :total-input-tokens (fnil + 0) (:input-token-count usage))
                     (update :total-output-tokens (fnil + 0) (:output-token-count usage))
                     (update :total-tokens (fnil + 0) (:total-token-count usage))
                     (update :request-count (fnil inc 0)))))))}))
