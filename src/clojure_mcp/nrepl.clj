(ns clojure-mcp.nrepl
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [nrepl.core :as nrepl]
   [nrepl.misc :as nrepl.misc]
   [nrepl.transport :as transport]
   [clojure-mcp.dialects :as dialects]
   [taoensso.timbre :as log])
  (:import [java.io Closeable]))

(defn- get-state [service]
  (get service ::state))

(defn- make-port-entry
  "Creates a new port entry with default values."
  []
  {:sessions {}
   :current-ns {}
   :env-type nil
   :initialized? false
   :project-dir nil})

(defn create
  "Creates an nREPL service map with state tracking.
   If port is provided, creates an initial entry for that port."
  ([] (create nil))
  ([config]
   (let [port (:port config)
         initial-state {:ports (if port {port (make-port-entry)} {})}
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

;; -----------------------------------------------------------------------------
;; Per-port state accessors for env-type, initialized?, and project-dir
;; -----------------------------------------------------------------------------

(defn get-port-env-type
  "Returns the env-type for the service's current port, or nil if not yet detected."
  [service]
  (let [port (:port service)
        state @(get-state service)]
    (get-in state [:ports port :env-type])))

(defn set-port-env-type!
  "Sets the env-type for the service's current port."
  [service env-type]
  (let [port (:port service)]
    (swap! (get-state service) assoc-in [:ports port :env-type] env-type)))

(defn port-initialized?
  "Returns true if the service's current port has been initialized."
  [service]
  (let [port (:port service)
        state @(get-state service)]
    (get-in state [:ports port :initialized?] false)))

(defn set-port-initialized!
  "Marks the service's current port as initialized."
  [service]
  (let [port (:port service)]
    (swap! (get-state service) assoc-in [:ports port :initialized?] true)))

(defn get-port-project-dir
  "Returns the project directory for the service's current port, or nil."
  [service]
  (let [port (:port service)
        state @(get-state service)]
    (get-in state [:ports port :project-dir])))

(defn set-port-project-dir!
  "Sets the project directory for the service's current port."
  [service project-dir]
  (let [port (:port service)]
    (swap! (get-state service) assoc-in [:ports port :project-dir] project-dir)))

(defn ensure-port-entry!
  "Ensures the port has an entry in the state atom. Used for lazy port addition.
   Returns the service unchanged."
  [service]
  (let [port (:port service)]
    (swap! (get-state service) update :ports
           (fn [ports]
             (if (contains? ports port)
               ports
               (assoc ports port (make-port-entry))))))
  service)

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

(defn- eval-code-internal
  "Internal eval - opens connection, no initialization.
   Used by init code to avoid circular calls."
  [service code & {:keys [session-type]}]
  (let [conn (open-connection service)]
    (try
      (let [{:keys [responses]} (eval-code* service conn code {:session-type session-type})]
        responses)
      (finally
        (close-connection conn)))))

;; Forward declaration - ensure-port-initialized! is defined later
(declare ensure-port-initialized!)

(defn eval-code
  "Evaluates code synchronously using a new connection.
   Triggers lazy port initialization if not already initialized.
   Returns a sequence of response messages."
  [service code & {:keys [session-type]}]
  (ensure-port-initialized! service)
  (eval-code-internal service code :session-type session-type))

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

;; -----------------------------------------------------------------------------
;; Dialect-aware functions (moved from dialects.clj to avoid circular deps)
;; -----------------------------------------------------------------------------

(defn detect-shadow-cljs?
  "Detects if the nREPL server is shadow-cljs by evaluating '1' and checking
   if the response ns is 'shadow.user'. Uses eval-code-internal to avoid
   triggering full initialization."
  [service]
  (try
    (let [responses (eval-code-internal service "1" :session-type :tools)
          combined (nrepl/combine-responses responses)]
      (= "shadow.user" (:ns combined)))
    (catch Exception _
      false)))

(defn shadow-cljs-mode?
  "Detects if a shadow-cljs session is currently in CLJS mode by evaluating
   cljs.user/*clojurescript-version*. If it returns a value (not nil/error),
   the session is in CLJS mode.
   Uses the specified session-type (defaults to :default)."
  ([service]
   (shadow-cljs-mode? service :default))
  ([service session-type]
   (try
     (let [responses (eval-code-internal service
                                         "cljs.user/*clojurescript-version*"
                                         :session-type session-type)
           combined (nrepl/combine-responses responses)]
       ;; If we get a value and it's not nil, we're in CLJS mode
       (and (:value combined)
            (not= "nil" (:value combined))
            (nil? (:ex combined))))
     (catch Exception _
       false))))

(defn detect-nrepl-env-type
  "Detects the nREPL environment type by querying the server's describe op.
   Also detects shadow-cljs which runs on top of Clojure."
  [service]
  (when-let [{:keys [versions]} (describe service)]
    (cond
      ;; Check for shadow-cljs first (runs on top of Clojure)
      (and (get versions :clojure) (detect-shadow-cljs? service)) :shadow
      (get versions :clojure) :clj
      (get versions :babashka) :bb
      (get versions :basilisp) :basilisp
      (get versions :sci-nrepl) :scittle
      :else :unknown)))

(defmulti ^:private fetch-project-directory-helper
  "Helper multimethod for fetching project directory based on env-type."
  (fn [nrepl-env-type _service] nrepl-env-type))

(defmethod fetch-project-directory-helper :default [nrepl-env-type service]
  ;; default to fetching from the nrepl
  ;; Uses eval-code-internal to avoid triggering full init (we're called during init)
  (when-let [exp (dialects/fetch-project-directory-exp nrepl-env-type)]
    (try
      (let [result-value (->> (eval-code-internal service exp :session-type :tools)
                              nrepl/combine-responses
                              :value)]
        result-value)
      (catch Exception e
        (log/warn e "Failed to fetch project directory")
        nil))))

(defmethod fetch-project-directory-helper :scittle [_ service]
  (when-let [desc (describe service)]
    (some-> desc :aux :cwd io/file (.getCanonicalPath))))

(defn fetch-project-directory
  "Fetches the project directory for the given nREPL client.
   If project-dir is provided in opts, returns it directly.
   Otherwise, evaluates environment-specific expression to get it."
  [service nrepl-env-type project-dir-arg]
  (if project-dir-arg
    (.getCanonicalPath (io/file project-dir-arg))
    (let [raw-result (fetch-project-directory-helper nrepl-env-type service)]
      ;; nrepl sometimes returns strings with extra quotes and in a vector
      (if (and (vector? raw-result) (= 1 (count raw-result)) (string? (first raw-result)))
        (str/replace (first raw-result) #"^\"|\"$" "")
        raw-result))))

(defn initialize-environment
  "Initializes the environment by evaluating dialect-specific expressions.
   Uses eval-code-internal to avoid circular init calls.
   Returns the service unchanged."
  [service nrepl-env-type]
  (log/debug "Initializing environment for" nrepl-env-type)
  (when-let [init-exps (not-empty (dialects/initialize-environment-exp nrepl-env-type))]
    (doseq [exp init-exps]
      (eval-code-internal service exp)))
  service)

(defn load-repl-helpers
  "Loads REPL helper functions appropriate for the environment.
   Uses eval-code-internal to avoid circular init calls."
  [service nrepl-env-type]
  (when-let [helper-exps (not-empty (dialects/load-repl-helpers-exp nrepl-env-type))]
    (doseq [exp helper-exps]
      (eval-code-internal service exp :session-type :tools)))
  service)

;; -----------------------------------------------------------------------------
;; Lazy per-port initialization
;; -----------------------------------------------------------------------------

(defn detect-and-store-env-type!
  "Detects the environment type for the given service's port and stores it.
   Returns the detected env-type. Does nothing if already detected."
  [service]
  (or (get-port-env-type service)
      (let [env-type (detect-nrepl-env-type service)]
        (set-port-env-type! service env-type)
        (log/info "Detected env-type for port" (:port service) ":" env-type)
        env-type)))

(defn initialize-port!
  "Initializes a port: runs init expressions and loads helpers.
   Does nothing if already initialized. Not thread-safe - use ensure-port-initialized!
   Returns the service."
  [service]
  (ensure-port-entry! service)
  (when-not (port-initialized? service)
    (log/info "Initializing port" (:port service))
    (let [env-type (detect-and-store-env-type! service)]
      (initialize-environment service env-type)
      (load-repl-helpers service env-type)
      (set-port-initialized! service)
      (log/info "Port" (:port service) "initialized successfully")))
  service)

(defn ensure-port-initialized!
  "Ensures a port is initialized before use.
   Returns the service unchanged."
  [service]
  (cond-> service
    (not (port-initialized? service)) initialize-port!))

(defn with-port
  "Returns a service map configured for the specified port.
   If port is nil or same as current, returns service unchanged.
   Ensures the port entry exists in state but does NOT initialize."
  [service port]
  (if (or (nil? port) (= port (:port service)))
    service
    (-> service
        (assoc :port port)
        ensure-port-entry!)))

(defn with-port-initialized
  "Returns a service map for the specified port, ensuring it is initialized.
   Combines with-port and ensure-port-initialized! for convenience."
  [service port]
  (-> service
      (with-port port)
      ensure-port-initialized!))

(defn read-nrepl-port-file
  "Reads the .nrepl-port file from the given directory.
   Returns the port number if found and valid, nil otherwise."
  [dir]
  (when dir
    (let [port-file (io/file dir ".nrepl-port")]
      (when (.exists port-file)
        (try
          (-> (slurp port-file)
              str/trim
              Integer/parseInt)
          (catch Exception _ nil))))))
