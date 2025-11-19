(ns clojure-mcp.nrepl
  (:require
   [clojure.main]
   [nrepl.core :as nrepl]
   [nrepl.misc :as nrepl.misc]
   [nrepl.transport]
   [taoensso.timbre :as log]
   [clojure.edn]))

;; callback system
(defn add-callback! [{:keys [::state]} id f]
  (swap! state assoc-in [:id-callbacks id] f))

(defn remove-callback! [{:keys [::state]} id]
  (swap! state update :id-callbacks dissoc id))

(defn set-current-eval-id! [{:keys [::state]} id]
  (swap! state assoc :current-eval-id id))

(defn remove-current-eval-id! [{:keys [::state]}]
  (swap! state dissoc :current-eval-id))

(defn dispatch-response! [{:keys [::state]} msg]
  (doseq [f (vals (get @state :id-callbacks))]
    (f msg)))

;; message callback-api
(defn new-id [] (nrepl.misc/uuid))

(defn select-key [key shouldbe k]
  (fn [msg]
    (when (= (get msg key) shouldbe)
      (k msg))))

(defn on-key [key callback k]
  (fn [msg]
    (when-let [v (get msg key)]
      (callback v))
    (k msg)))

(defn session-id [session' id' k]
  (->> k
       (select-key :id id')
       (select-key :session session')))

(defn out-err [print-out print-err k]
  (->> k
       (on-key :out print-out)
       (on-key :err print-err)))

(defn value [callback k]
  (on-key :value callback k))

(defn handle-statuses [pred callback k]
  (fn [{:keys [status] :as msg}]
    (when (some pred status)
      (callback msg))
    (k msg)))

(defn done [callback k]
  (handle-statuses #{"done" "interrupt"} callback k))

(defn error [callback k]
  (handle-statuses #{"error" "eval-error"} callback k))

(defn need-input [callback k]
  (handle-statuses #{"need-input"} callback k))

(defn send-msg! [{:keys [::state] :as service} {:keys [session id] :as msg} callback]
  (assert session)
  (assert id)
  (add-callback!
   service id
   (->> callback
        (error (fn [_] (remove-callback! service id)))
        (done (fn [_] (remove-callback! service id)))
        (session-id session id)))
  (tap> msg)
  (nrepl.transport/send (:conn @state) msg))

(defn eval-session [{:keys [::state]}]
  (get @state :session))

(defn tool-session [{:keys [::state]}]
  (get @state :tool-session))

;; session state management in general is getting a little messy
;; parallel evals seem possible
(defn ns-session
  "returns session for when the use wants to temporarily change to a ns
  All requests to this session are intended to have the ns declared."
  [{:keys [::state]}]
  (get @state :ns-session))

(defn current-ns
  ([{:keys [::state]} session]
   (get-in @state [:current-ns session]))
  ([{:keys [::state]} session new-ns]
   (swap! state assoc-in [:current-ns session] new-ns)))

(defn new-message [service msg]
  (merge
   {:session (eval-session service)
    :id (new-id)}
   msg))

(defn new-tool-message [service msg]
  (new-message
   service
   (merge {:session (tool-session service)}
          msg)))

(def truncation-length 10000) ;; 20000 roughly 250 lines

(defn eval-code-msg
  [service code-str msg' k]
  (let [msg (merge
             msg'
             {:op "eval"
              :code code-str
              :nrepl.middleware.print/print "nrepl.util.print/pprint"
              ;; need to be able to set this magic number
              :nrepl.middleware.print/quota truncation-length})
        {:keys [id session] :as message} (new-message service msg)
        prom (promise)
        finish (fn [_]
                 (deliver prom ::done)
                 (remove-current-eval-id! service))]
    (set-current-eval-id! service id)
    (send-msg! service
               message
               (->> k
                    (on-key :ns #(current-ns service session %))
                    (done finish)
                    (error finish)))
    prom))

(defn eval-code-help [service code-str k]
  (eval-code-msg service code-str {} k))

(defn eval-code [service code-str k]
  @(eval-code-help service code-str k))

(defn interrupt [{:keys [::state] :as service}]
  ;; TODO having a timeout and then calling the
  ;; callback with a done message could prevent
  ;; terminal lockup in extreme cases
  (let [{:keys [current-eval-id]} @state]
    (when current-eval-id
      (send-msg!
       service
       (new-message service {:op "interrupt" :interrupt-id current-eval-id})
       identity))))

(defn lookup [service symbol]
  (let [prom (promise)]
    (send-msg! service
               (new-tool-message service {:op "lookup" :sym symbol})
               (->> identity
                    (done #(deliver prom
                                    (some-> %
                                            :info
                                            not-empty
                                            (update :arglists clojure.edn/read-string))))))
    (deref prom 400 nil)))

(defn completions [service prefix]
  (let [prom (promise)]
    (send-msg! service
               (new-tool-message service {:op "completions" :prefix prefix})
               (->> identity
                    (done #(deliver prom (get % :completions)))))
    (deref prom 400 nil)))

(defn tool-eval-code [service code-str]
  (let [prom (promise)]
    (send-msg! service
               (new-tool-message service {:op "eval" :code code-str})
               (->> identity
                    (value #(deliver prom %))))
    (deref prom 400 nil)))

(defn ls-middleware [service]
  (let [prom (promise)]
    (send-msg! service
               (new-tool-message service {:op "ls-middleware"})
               (->> identity
                    (on-key :middleware #(deliver prom %))))
    (deref prom 400 nil)))

(defn describe [service]
  (let [prom (promise)]
    (send-msg! service
               (new-tool-message service {:op "describe"})
               (->> identity
                    (done #(deliver prom %))))
    (deref prom 600 nil)))

(defn new-session [service]
  (let [prom (promise)]
    (send-msg! service
               ;; this will clone the tool session
               (new-tool-message service {:op "clone"})
               (->> identity
                    (done #(deliver prom (get % :new-session)))))
    (deref prom 600 nil)))

(defn send-input [service input]
  (send-msg! service
             (new-message service {:op "stdin" :stdin (when input
                                                        (str input "\n"))})
             identity))

(defn stop-polling [{:keys [::state]}]
  (swap! state dissoc :response-poller))

(defn polling? [{:keys [::state]}]
  (:response-poller @state))

(declare create)

(defn poll-for-responses [{:keys [::state] :as options} _conn]
  (let [retries (atom 60)]
    (loop []
      (when (polling? options)
        (let [continue
              (try
                (when-let [resp (nrepl.transport/recv (:conn @state) 100)]
                  (reset! retries 60)
                  #_(tap> resp)
                  (dispatch-response! options resp))
                :success
                (catch java.io.IOException e
                  (log/error e "nREPL connection failure 1")
                  :retry)
                (catch Throwable e
                  (log/error e "nREPL connection failure 2")
                  (some-> options :repl/error (reset! e))
                  :retry))]
          (cond
            (= :retry continue)
            (if (< 0 @retries)
              (do (Thread/sleep 1000)
                  (log/info (str "nRPEL Trying to reconnect to " (:port options)))
                  (try
                    (create options)
                    (catch Exception e
                      (log/error e "Reconnect failed")))
                  (swap! retries dec)
                  (recur))
              (stop-polling options))
            (= :success continue)
            (recur)))))))

(defn start-polling [{:keys [::state] :as service}]
  (let [response-poller (Thread. ^Runnable (bound-fn [] (poll-for-responses service (:conn @state))))]
    (swap! state assoc :response-poller response-poller)
    (doto ^Thread response-poller
      (.setName "Rebel Readline nREPL response poller")
      (.setDaemon true)
      (.start))))

(defn create
  ([] (create nil))
  ([config]
   (let [conn (apply nrepl/connect
                     (flatten (seq (select-keys config [:port :host :tls-keys-file]))))
         client (nrepl/client conn Long/MAX_VALUE)
         session (nrepl/new-session client)
         tool-session (nrepl/new-session client)
         ;; ns-session always has an ns declared in evals
         ns-session (nrepl/new-session client)
         state (::state config (atom {}))]
     (swap! state assoc
            :conn conn
            :client client
            :session session
            :ns-session ns-session
            :tool-session tool-session)
     (assoc config
            :repl/error (atom nil)
            ::state state))))

