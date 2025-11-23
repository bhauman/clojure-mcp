(ns clojure-mcp.nrepl
  (:require
   [nrepl.core :as nrepl]
   [nrepl.misc :as nrepl.misc]
   [nrepl.transport :as transport])
  (:import [java.io Closeable]))

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

(defn open-connection
  "Opens an nREPL transport and client pair for the given service.
  Callers are responsible for closing via `close-connection`."
  ([service]
   (open-connection service Long/MAX_VALUE))
  ([service timeout-ms]
   (let [transport (connect service)
         client (nrepl/client transport timeout-ms)]
     {:transport transport
      :client client})))

(defn close-connection [{:keys [transport]}]
  (when transport
    (.close ^Closeable transport)))

(defn new-eval-id []
  (nrepl.misc/uuid))

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

(defn ensure-session
  "Ensures a session exists for the given session-type using the provided connection map."
  ([service conn]
   (ensure-session service conn :default))
  ([service {:keys [client]} session-type]
   (ensure-session! client service session-type)))

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

(defn eval-code*
  "Low-level eval helper that executes using an existing connection map.
  Returns a map containing the responses plus the session/id used."
  [service {:keys [client]} code {:keys [session-type session-id eval-id] :as _opts}]
  (let [session-type (or session-type :default)
        session-id (or session-id (ensure-session! client service session-type))
        eval-id (or eval-id (new-eval-id))
        msg {:op "eval"
             :code code
             :session session-id
             :id eval-id
             :nrepl.middleware.print/print "nrepl.util.print/pprint"
             :nrepl.middleware.print/quota truncation-length}
        responses (doall (nrepl/message client msg))]
    ;; Update current-ns if present in any response
    (doseq [resp responses]
      (when-let [new-ns (:ns resp)]
        (update-current-ns! service session-type new-ns)))
    {:responses responses
     :session-id session-id
     :eval-id eval-id
     :session-type session-type}))

(defn eval-code
  "Evaluates code synchronously using a new connection.
   Returns a sequence of response messages."
  [service code & {:keys [session-type]}]
  (let [conn (open-connection service)]
    (try
      (let [{:keys [responses]} (eval-code* service conn code {:session-type session-type})]
        responses)
      (finally
        (close-connection conn)))))

(defn interrupt*
  "Sends an interrupt over an existing connection using the provided session/id."
  [{:keys [transport]} session-id eval-id]
  (when (and transport session-id eval-id)
    (transport/send transport {:op "interrupt"
                               :session session-id
                               :interrupt-id eval-id})))

(defn interrupt
  "Interrupts an eval by creating a short-lived connection."
  [service session-id eval-id]
  (let [conn (open-connection service 1000)]
    (try
      (interrupt* conn session-id eval-id)
      (finally
        (close-connection conn)))))

(defn describe
  "Returns the nREPL server's description, synchronously."
  [service]
  (with-open [conn (connect service)]
    (let [client (nrepl/client conn 10000)]
      (nrepl/combine-responses (nrepl/message client {:op "describe"})))))
