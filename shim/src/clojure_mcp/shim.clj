(ns clojure-mcp.shim
  "The stdio MCP server an agent's client launches. It finds the shared
   clojure-mcp daemon for its pinned version, starting it if needed, and
   relays newline-delimited JSON-RPC between stdio and the daemon's
   /p/<project dir>/mcp endpoint.

   `initialize` is answered here, at once, so a cold daemon (15 s or more of
   JVM start, longer on a first git-deps fetch) never trips a client's
   connect timeout; later requests wait for the daemon. If the daemon goes
   away mid-session it is restarted and the session replayed.

   Usage:
     bb -Sdeps '{:deps {io.github.bhauman/clojure-mcp {:git/url \"https://github.com/bhauman/clojure-mcp\" :git/sha \"<sha>\" :deps/root \"shim\"}}}' \\
        -m clojure-mcp.shim --sha <sha> [--local-root <dir>] [--warm] [--start-timeout-ms 180000]"
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.io BufferedReader File InputStream)
           (java.net ConnectException URLEncoder)
           (java.nio.channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)
           (java.security MessageDigest)))

(def ^:private markers [".git" "deps.edn" "bb.edn" "shadow-cljs.edn"])

(def ^:private jetty-deps
  "The daemon's HTTP deps, as in the :daemon alias."
  {'jakarta.servlet/jakarta.servlet-api {:mvn/version "6.1.0"}
   'org.eclipse.jetty/jetty-server {:mvn/version "11.0.20"}
   'org.eclipse.jetty/jetty-servlet {:mvn/version "11.0.20"}})

(defn project-root
  "Nearest ancestor of `cwd` (inclusive) holding a project marker, else `cwd`."
  [cwd]
  (let [start (.getCanonicalFile (io/file cwd))]
    (str (or (->> (iterate #(.getParentFile ^File %) start)
                  (take-while some?)
                  (some (fn [d] (when (some #(.exists (io/file d %)) markers) d))))
             start))))

(defn mcp-path
  "The daemon path for project `dir`: each segment percent-encoded, slashes
   literal (Jetty refuses %2F)."
  [dir]
  (str "/p"
       (string/join (map #(str "/" (string/replace (URLEncoder/encode ^String % "UTF-8") "+" "%20"))
                      (remove string/blank? (string/split dir #"/"))))
       "/mcp"))

(defn- sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (string/join (map #(format "%02x" %) d))))

(defn version
  "The daemon version this shim needs: the pinned sha, or for a local
   checkout a name derived from its path."
  [{:keys [sha local-root]}]
  (if local-root
    (str "local-" (subs (sha256-hex (.getCanonicalPath (io/file local-root))) 0 12))
    sha))

(defn state-dir [version]
  (let [cache (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                  (str (System/getProperty "user.home") "/.cache"))]
    (str (io/file cache "clojure-mcp" "daemons" version))))

(defn- slurp-trim [f] (string/trim (slurp f)))

(defn live-daemon
  "{:port :token} of a daemon of `version` answering in `dir`, else nil."
  [dir version]
  (let [pf (io/file dir "port")
        tf (io/file dir "token")]
    (when (and (.exists pf) (.exists tf))
      (try
        (let [port (parse-long (slurp-trim pf))
              token (slurp-trim tf)
              r (http/get (str "http://127.0.0.1:" port "/health")
                          {:headers {"Authorization" (str "Bearer " token)} :throw false :timeout 2000})]
          (when (and (= 200 (:status r)) (= version (get (json/parse-string (:body r)) "version")))
            {:port port :token token}))
        (catch Exception _ nil)))))

(defn daemon-command [{:keys [sha local-root]} version]
  ["clojure" "-Sdeps"
   (pr-str {:deps (assoc jetty-deps 'io.github.bhauman/clojure-mcp
                         (if local-root
                           {:local/root (.getCanonicalPath (io/file local-root))}
                           {:git/url "https://github.com/bhauman/clojure-mcp" :git/sha sha}))})
   "-X" "clojure-mcp.daemon/start" ":version" (pr-str version)])

(defn start-detached!
  "Runs `cmd` (argv) in `dir`, detached from this process's stdio, appending
   its output to daemon.log there."
  [dir cmd]
  (let [log (io/file dir "daemon.log")]
    (apply p/process {:dir dir :out :append :out-file log :err :append :err-file log} cmd)))

(defn- spawn-daemon!
  "Starts the daemon. It outlives the shim: other sessions share it."
  [{:keys [state-dir version] :as ctx}]
  (start-detached! state-dir (daemon-command ctx version)))

(defn- log-tail [state-dir n]
  (let [f (io/file state-dir "daemon.log")]
    (if (.exists f)
      (string/join "\n" (take-last n (string/split-lines (slurp f))))
      "(no daemon.log)")))

(defn- try-lock
  "The spawn lock, or nil when another process (or, in tests, another shim in
   this process) holds it."
  [^FileChannel ch]
  (try (.tryLock ch) (catch Exception _ nil)))

(defn ensure-daemon!
  "{:port :token} of a live daemon, spawning one if none answers. A file lock
   makes concurrent shims spawn it once; the others wait for its port file."
  [{:keys [state-dir version spawn! start-timeout-ms] :as ctx}]
  (.mkdirs (io/file state-dir))
  (or (live-daemon state-dir version)
      (let [deadline (+ (System/currentTimeMillis) start-timeout-ms)]
        (with-open [ch (FileChannel/open (.toPath (io/file state-dir "spawn.lock"))
                                         (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
          (loop [lock nil spawned? false]
            (or (live-daemon state-dir version)
                (do
                  (when (> (System/currentTimeMillis) deadline)
                    (throw (ex-info (str "clojure-mcp daemon " version " did not start within "
                                         (quot start-timeout-ms 1000) " s. Last lines of "
                                         state-dir "/daemon.log:\n" (log-tail state-dir 40))
                                    {:state-dir state-dir})))
                  (let [lock (or lock (try-lock ch))
                        spawn? (and lock (not spawned?))]
                    (when spawn? (spawn! ctx))
                    (Thread/sleep 200)
                    (recur lock (or spawned? spawn?))))))))))

(defn- headers [{:keys [token session-id env-id protocol-version]}]
  (cond-> {"Authorization" (str "Bearer " token)
           "Accept" "application/json, text/event-stream"
           "Content-Type" "application/json"}
    env-id (assoc "X-Clojure-Mcp-Env" env-id)
    session-id (assoc "Mcp-Session-Id" session-id)
    protocol-version (assoc "MCP-Protocol-Version" protocol-version)))

(defn- base-url [{:keys [port]}] (str "http://127.0.0.1:" port))

(defn- sse-messages
  "The `data` of each SSE event read from `in`, in order, calling `f` on each."
  [^InputStream in f]
  (with-open [r ^BufferedReader (io/reader in)]
    (loop [data []]
      (when-let [line (.readLine r)]
        (cond
          (string/blank? line) (do (when (seq data) (f (string/join "\n" data))) (recur []))
          (string/starts-with? line "data:") (recur (conj data (string/triml (subs line 5))))
          :else (recur data))))))

(defn- relay-response!
  "Writes the JSON-RPC message(s) in a daemon response to the client."
  [{:keys [emit]} resp id]
  (let [ctype (str (get-in resp [:headers "content-type"]))
        status (:status resp)]
    (cond
      (= 202 status) (some-> ^InputStream (:body resp) .close)
      (and (= 200 status) (string/starts-with? ctype "text/event-stream")) (sse-messages (:body resp) emit)
      (= 200 status) (let [body (string/trim (slurp (:body resp)))] (when (seq body) (emit body)))
      :else (let [body (slurp (:body resp))]
              (when id
                (emit (json/generate-string {:jsonrpc "2.0" :id id
                                             :error {:code -32603
                                                     :message (str "clojure-mcp daemon answered " status ": " body)}})))))))

(defn- open-session!
  "Forwards this shim's env, replays the client's initialize and
   notifications/initialized, and returns the connection."
  [{:keys [project-dir init] :as ctx} daemon]
  (let [env-id (-> (http/post (str (base-url daemon) "/env")
                              {:headers (headers daemon)
                               :body (json/generate-string (into {} (System/getenv)))})
                   :body json/parse-string (get "id"))
        conn (assoc daemon :env-id env-id :url (str (base-url daemon) (mcp-path project-dir)))
        resp (http/post (:url conn) {:headers (headers conn) :body (json/generate-string @init)
                                     :as :stream :throw false})
        result (atom nil)]
    (relay-response! (assoc ctx :emit #(reset! result (json/parse-string % true))) resp nil)
    (let [conn (assoc conn
                      :session-id (get-in resp [:headers "mcp-session-id"])
                      :protocol-version (get-in @result [:result :protocolVersion]))]
      (http/post (:url conn) {:headers (headers conn) :throw false
                              :body (json/generate-string {:jsonrpc "2.0" :method "notifications/initialized"})})
      conn)))

(defn- start-connect! [{:keys [conn] :as ctx}]
  (let [p (promise)]
    (reset! conn p)
    (future (deliver p (try (open-session! ctx (ensure-daemon! ctx))
                            (catch Throwable t t))))
    p))

(defn- reconnect!
  "Starts a new connection unless another thread already replaced `failed`."
  [{:keys [conn lock] :as ctx} failed]
  (locking lock
    (when (identical? @conn failed)
      (start-connect! ctx))))

(defn- fail! [{:keys [emit exit!]} id ^Throwable t]
  (binding [*out* *err*] (println (ex-message t)))
  (when id
    (emit (json/generate-string {:jsonrpc "2.0" :id id :error {:code -32603 :message (ex-message t)}})))
  (future (Thread/sleep 500) (exit! 1)))

(defn- lost!
  "Answers a call whose connection broke after it reached the daemon."
  [emit id ^Exception e]
  (when id
    (emit (json/generate-string {:jsonrpc "2.0" :id id
                                 :error {:code -32603
                                         :message (str "Lost the clojure-mcp daemon during this call: " (ex-message e))}}))))

(defn- forward!
  "Sends one client message to the daemon and relays the answer. A refused
   connection (the request never reached a daemon) restarts the daemon and
   is retried once; anything else is not, since a tool call may have run, and
   is answered with an error."
  [{:keys [conn emit start-timeout-ms] :as ctx} line id]
  (loop [retried? false]
    (let [p @conn
          c (deref p (+ start-timeout-ms 10000) (ex-info "clojure-mcp daemon connection timed out" {}))]
      (if (instance? Throwable c)
        (fail! ctx id c)
        (let [resp (try (http/post (:url c) {:headers (headers c) :body line :as :stream :throw false})
                        (catch Exception e e))]
          (cond
            (instance? ConnectException resp) (if retried?
                                                (fail! ctx id resp)
                                                (do (reconnect! ctx p) (recur true)))
            (instance? Exception resp) (lost! emit id resp)
            :else (try (relay-response! ctx resp id)
                       (catch Exception e (lost! emit id e)))))))))

(defn- listen!
  "Relays server->client messages from the session's GET stream, if the
   daemon offers one."
  [{:keys [conn emit running]}]
  (future
    (loop []
      (when @running
        (let [c @@conn
              status (when (map? c)
                       (try
                         (let [resp (http/get (:url c) {:headers (assoc (headers c) "Accept" "text/event-stream")
                                                        :as :stream :throw false})]
                           (when (= 200 (:status resp)) (sse-messages (:body resp) emit))
                           (:status resp))
                         (catch Exception _ nil)))]
          (when-not (= 405 status)
            (Thread/sleep 1000)
            (recur)))))))

(defn context
  "The shim's state for one stdio session. `opts` needs :project-dir
   :version :state-dir :spawn! :start-timeout-ms :read-line :emit :exit!."
  [opts]
  (assoc opts :conn (atom nil) :init (atom nil) :lock (Object.) :running (atom true)))

(defn relay!
  "Relays until stdin ends, then closes the daemon session and exits 0."
  [{:keys [read-line emit exit! conn init running version] :as ctx}]
  (loop []
    (if-let [line (read-line)]
      (do
        (when-not (string/blank? line)
          (let [msg (try (json/parse-string line true) (catch Exception _ nil))]
            (case (:method msg)
              "initialize"
              (do (reset! init msg)
                  (emit (json/generate-string
                         {:jsonrpc "2.0" :id (:id msg)
                          :result {:protocolVersion (get-in msg [:params :protocolVersion])
                                   :capabilities {:tools {:listChanged true}}
                                   :serverInfo {:name "clojure-mcp" :version version}}}))
                  (start-connect! ctx)
                  (listen! ctx))
              ;; sent for the client by open-session!, once the daemon is up
              "notifications/initialized" nil
              (if @conn
                (future (forward! ctx line (:id msg)))
                (when (:id msg)
                  (emit (json/generate-string {:jsonrpc "2.0" :id (:id msg)
                                               :error {:code -32002 :message "initialize first"}})))))))
        (recur))
      (do
        (reset! running false)
        (when-let [p @conn]
          (let [c (deref p 2000 nil)]
            (when (map? c)
              (try (http/delete (:url c) {:headers (headers c) :throw false :timeout 2000})
                   (catch Exception _ nil)))))
        (exit! 0)))))

(defn- parse-args [args]
  (loop [[a b & more :as args] args opts {}]
    (case a
      nil opts
      "--sha" (recur more (assoc opts :sha b))
      "--local-root" (recur more (assoc opts :local-root b))
      "--start-timeout-ms" (recur more (assoc opts :start-timeout-ms (parse-long b)))
      "--warm" (recur (rest args) (assoc opts :warm? true))
      (throw (ex-info (str "Unknown argument: " a) {})))))

(defn -main [& args]
  (let [{:keys [sha local-root warm? start-timeout-ms] :as opts} (parse-args args)]
    (when-not (or sha local-root)
      (binding [*out* *err*] (println "clojure-mcp shim: pass --sha <clojure-mcp sha> or --local-root <checkout>"))
      (System/exit 2))
    (let [v (version opts)
          stdin (io/reader System/in)
          ctx (context (merge opts
                              {:project-dir (project-root (System/getProperty "user.dir"))
                               :version v
                               :state-dir (state-dir v)
                               :spawn! spawn-daemon!
                               :start-timeout-ms (or start-timeout-ms 180000)
                               :read-line #(.readLine ^BufferedReader stdin)
                               :emit (let [out (Object.)]
                                       (fn [^String s] (locking out (println s) (flush))))
                               :exit! #(System/exit %)}))]
      (if warm?
        (do (println (:port (ensure-daemon! ctx))) (System/exit 0))
        (relay! ctx)))))
