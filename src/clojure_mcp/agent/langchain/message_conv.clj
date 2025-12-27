(ns clojure-mcp.agent.langchain.message-conv
  "Round-trip conversion between LangChain4j ChatMessage lists and EDN data."
  (:require
   [clojure.data.json :as json]
   [langchain4clj.messages :as lc-msg]))

(defn messages->edn
  "Convert Java ChatMessage list to EDN vector."
  [msgs]
  (-> (lc-msg/messages->json msgs)
      (json/read-str :key-fn keyword)))

(defn edn->messages
  "Convert EDN vector to Java ChatMessage list."
  [edn-msgs]
  (-> edn-msgs
      json/write-str
      lc-msg/json->messages))

(defn message->edn
  "Convert a single Java ChatMessage to EDN."
  [java-msg]
  (some-> java-msg
          list
          messages->edn
          first))

(defn edn->message
  "Convert a single EDN message to Java ChatMessage."
  [edn-msg]
  (some-> edn-msg
          list
          edn->messages
          first))

(defn parse-tool-arguments
  "Parse JSON string arguments in toolExecutionRequests to EDN."
  [ai-message]
  (if-let [requests (:toolExecutionRequests ai-message)]
    (assoc ai-message
           :toolExecutionRequests
           (mapv (fn [req]
                   (update req :arguments
                           (fn [args]
                             (if (string? args)
                               (json/read-str args :key-fn keyword)
                               args))))
                 requests))
    ai-message))

(defn parse-messages-tool-arguments
  "Parse tool arguments in all AI messages within a message list."
  [messages]
  (mapv (fn [msg]
          (if (= "AI" (:type msg))
            (parse-tool-arguments msg)
            msg))
        messages))
