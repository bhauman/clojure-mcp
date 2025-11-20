(ns clojure-mcp.nrepl
  (:require
   [nrepl.core :as nrepl]
   [nrepl.misc :as nrepl.misc]))

(defn- get-state [service]
  (get service ::state))

(defn create
  ([] (create nil))
  ([config]
   (let [port (:port config)
         initial-state {:ports (if port {port {:sessions {}}} {})}
         state (atom initial-state)]
     (assoc config ::state state))))

(defn- connect [service]
  (let [{:keys [host port]} service]
    (nrepl/connect :host (or host "localhost") :port port)))

(defn- get-stored-session [service session-type]
  (let [port (:port service)
        state @(get-state service)]
    (get-in state [:ports port :sessions session-type])))

(defn- update-stored-session! [service session-type session-id]
  (let [port (:port service)]
    (swap! (get-state service) assoc-in [:ports port :sessions session-type] session-id)))

(defn- session-valid? [client session-id]
  (try
    (let [sessions (-> (nrepl/message client {:op "ls-sessions"})
                       nrepl/combine-responses
                       :sessions)]
      (contains? (set sessions) session-id))
    (catch Exception _ false)))

(defn- ensure-session! [client service session-type]
  (let [stored-id (get-stored-session service session-type)]
    (if (and stored-id (session-valid? client stored-id))
      stored-id
      (let [new-id (nrepl/new-session client)]
        (update-stored-session! service session-type new-id)
        new-id))))

(defn current-ns
  "Returns the current namespace for the given session type."
  [service session-type]
  (let [port (:port service)
        state @(get-state service)]
    (get-in state [:ports port :current-ns session-type])))

(defn- update-current-ns! [service session-type new-ns]
  (let [port (:port service)]
    (swap! (get-state service) assoc-in [:ports port :current-ns session-type] new-ns)))

(def truncation-length 10000)

(defn eval-code
  "Evaluates code synchronously using a new connection.
   Returns a sequence of response messages."
  [service code & {:keys [session-type] :or {session-type :default}}]
  (with-open [conn (connect service)]
    (let [client (nrepl/client conn Long/MAX_VALUE)
          session-id (ensure-session! client service session-type)
          id (nrepl.misc/uuid)
          msg {:op "eval"
               :code code
               :session session-id
               :id id
               :nrepl.middleware.print/print "nrepl.util.print/pprint"
               :nrepl.middleware.print/quota truncation-length}]
      (swap! (get-state service) assoc :current-eval-id id)
      (try
        (let [responses (doall (nrepl/message client msg))]
          ;; Update current-ns if present in any response
          (doseq [resp responses]
            (when-let [new-ns (:ns resp)]
              (update-current-ns! service session-type new-ns)))
          responses)
        (finally
          (swap! (get-state service) dissoc :current-eval-id))))))

(defn interrupt [service]
  (let [state (get-state service)
        id (:current-eval-id @state)]
    (when id
      (with-open [conn (connect service)]
        (let [client (nrepl/client conn 1000)
              ;; Assuming default session for interrupt for now as per typical usage
              session-id (get-stored-session service :default)]
          (nrepl/message client {:op "interrupt" :session session-id :interrupt-id id}))))))

(defn describe
  "Returns the nREPL server's description, synchronously."
  [service]
  (with-open [conn (connect service)]
    (let [client (nrepl/client conn 10000)]
      (nrepl/combine-responses (nrepl/message client {:op "describe"})))))