(ns clojure-mcp.daemon.server
  "The daemon's HTTP side: MCP Streamable HTTP on 127.0.0.1, where the path
   /p/<project dir>/mcp says which project a session belongs to. One
   server-wide tool list; each call is dispatched to the calling project's
   registry entry under the calling session's environment.

   Even on localhost every request needs the bearer token from the state dir,
   a loopback Host and no Origin: nREPL's bencode is unreachable from a
   browser page, an HTTP endpoint that evaluates code is not."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure-mcp.config :as config]
            [clojure-mcp.config.schema :as schema]
            [clojure-mcp.core :as core]
            [clojure-mcp.daemon.registry :as registry]
            [clojure-mcp.daemon.state :as state]
            [clojure-mcp.tools :as tools]
            [taoensso.timbre :as log])
  (:import (io.modelcontextprotocol.common McpTransportContext)
           (io.modelcontextprotocol.server McpAsyncServer McpAsyncServerExchange McpServer
                                           McpTransportContextExtractor)
           (io.modelcontextprotocol.server.transport HttpServletStreamableServerTransportProvider)
           (io.modelcontextprotocol.spec McpSchema$ServerCapabilities)
           (jakarta.servlet DispatcherType Filter FilterChain)
           (jakarta.servlet.http HttpServlet HttpServletRequest HttpServletResponse)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.time Duration)
           (java.util EnumSet)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (org.eclipse.jetty.server Server ServerConnector)
           (org.eclipse.jetty.servlet FilterHolder ServletContextHandler ServletHolder)))

(def agent-tool-names #{"dispatch_agent" "architect" "code_critique" "clojure_edit_agent"})

(def ^:private eviction {:ttl-ms 1800000 :max-entries 50 :every 100})

(defn agent-backend-available?
  "Whether the sub-agent tools have a model to call: a provider key, or an
   `agent-model-fallback` that offers a model for `env` (see `start!`).
   Judged once, from the daemon's own environment, when it starts: MCP lists
   tools per server, not per session."
  [env agent-model-fallback]
  (boolean (or (some #(not-empty (get env %)) registry/provider-keys)
               (some-> agent-model-fallback (apply [env])))))

(defn- sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))]
    (string/join (map #(format "%02x" %) d))))

(defn- project-dir-of
  "The canonical project dir named by a /p/<dir>/mcp request path, each
   segment percent-decoded (slashes stay literal: Jetty refuses %2F)."
  [uri]
  (when-let [[_ path] (re-matches #"/p(/.*)/mcp" uri)]
    (.getCanonicalPath
     (io/file (string/join "/" (map #(URLDecoder/decode (string/replace % "+" "%2B") StandardCharsets/UTF_8)
                                    (string/split path #"/")))))))

(defn- context-extractor [envs]
  (reify McpTransportContextExtractor
    (extract [_ request]
      (let [^HttpServletRequest req request
            dir (project-dir-of (.getRequestURI req))
            env (some->> (.getHeader req "X-Clojure-Mcp-Env") (get @envs))
            ctx (cond-> {} dir (assoc "project-dir" dir) env (assoc "env" env))]
        (if (seq ctx) (McpTransportContext/create ctx) McpTransportContext/EMPTY)))))

(defn session-bindings
  "The bindings a tool call for `dir` runs under, for a session whose env is
   `env`. `agent-model-fallback` is the daemon's hook (see `start!`), or nil."
  [env dir agent-model-fallback]
  (cond-> {#'config/*session-env* env}
    agent-model-fallback
    (assoc #'config/*agent-model-fallback*
           #(some-> (agent-model-fallback env) (apply [dir])))))

(defn- dispatcher
  "A tool-fn that runs `tool-name` for the calling project, under the calling
   session's forwarded env (or `daemon-env` when the session sent none)."
  [registry tool-name calls daemon-env agent-model-fallback]
  (fn [exchange args k]
    (let [ctx (.transportContext ^McpAsyncServerExchange exchange)
          dir (.get ctx "project-dir")
          env (or (.get ctx "env") daemon-env)]
      (try
        (if-not dir
          (k ["Connect through /p/<absolute project dir>/mcp: this daemon serves many projects and the URL says which one."] true)
          (with-bindings (session-bindings env dir agent-model-fallback)
            (let [entry (registry/get-or-create! registry dir)]
              (when (zero? (mod (swap! calls inc) (:every eviction)))
                (future (registry/evict-stale! registry eviction)))
              (if (registry/tool-enabled? entry tool-name)
                ((get (:tool-fns entry) tool-name) exchange args k)
                (k [(str tool-name " is disabled for " dir " by its .clojure-mcp/config.edn"
                         " or by ENABLE_TOOLS / DISABLE_TOOLS in this session's environment")] true)))))
        (catch Exception e
          (log/error e "Dispatch failed:" tool-name dir)
          (k [(str "Could not serve " tool-name " for " dir ": " (ex-message e))] true))))))

(defn- tool-maps
  "Tool metadata, built once from a throwaway project dir."
  [env agent-model-fallback]
  (let [dir (.toFile (Files/createTempDirectory "clojure-mcp-daemon" (make-array FileAttribute 0)))]
    (try
      ;; like registry entries: [:env] refs are a session's business, not the
      ;; starting shell's
      (cond->> (tools/build-all-tools (atom (binding [schema/*validate-env-vars* false]
                                              (core/create-and-start-nrepl-connection {:project-dir (str dir)}))))
        (not (agent-backend-available? env agent-model-fallback)) (remove #(agent-tool-names (:name %))))
      (finally (.delete dir)))))

(defn- security-filter [token port last-activity]
  (let [expected (.getBytes (str "Bearer " token) StandardCharsets/UTF_8)]
    (reify Filter
      (doFilter [_ req resp chain]
        (let [^HttpServletRequest req req
              ^HttpServletResponse resp resp
              auth (.getHeader req "Authorization")]
          (cond
            (.getHeader req "Origin")
            (.sendError resp 403 "Origin not allowed")

            (not (contains? #{(str "127.0.0.1:" @port) (str "localhost:" @port)} (.getHeader req "Host")))
            (.sendError resp 403 "Host not allowed")

            (not (and auth (MessageDigest/isEqual (.getBytes auth StandardCharsets/UTF_8) expected)))
            (.sendError resp 401 "Unauthorized")

            :else
            (do (reset! last-activity (System/currentTimeMillis))
                (.doFilter ^FilterChain chain req resp))))))))

(defn- json-servlet [handler]
  (proxy [HttpServlet] []
    (service [req resp]
      (let [^HttpServletResponse resp resp
            [status body] (handler req)]
        (.setStatus resp status)
        (.setContentType resp "application/json")
        (.write (.getWriter resp) ^String (json/write-str body))))))

(defn- env-handler [envs]
  (fn [^HttpServletRequest req]
    (if (not= "POST" (.getMethod req))
      [405 {"error" "POST a JSON object of environment variables"}]
      (let [env (try (json/read-str (slurp (.getReader req))) (catch Exception _ nil))]
        (if-not (and (map? env) (every? string? (keys env)) (every? string? (vals env)))
          [400 {"error" "body must be a JSON object of string to string"}]
          (let [id (sha256-hex (json/write-str (into (sorted-map) env)))]
            (swap! envs assoc id env)
            [200 {"id" id}]))))))

(defn start!
  "Starts the daemon's HTTP server and writes its state files into
   `state-dir`. Returns {:port :token :stop! :stopped}; `stopped` is a promise
   delivered once the server is down (idle exit or stop!).

   `agent-model-fallback`, optional, gives the sub-agent tools a model when
   neither the agent's config nor the caller's provider keys do: a fn of a
   session's env that returns nil when it has no model for that env, else a
   fn of the project dir that returns a langchain4j ChatModel."
  [{:keys [version state-dir env idle-exit-ms agent-model-fallback]}]
  (state/ensure-dir! state-dir)
  (let [lock (or (state/lock! state-dir)
                 (throw (ex-info (str "Another clojure-mcp daemon " version " is already running from " state-dir)
                                 {:state-dir state-dir})))
        token (state/write-token! state-dir)
        port (promise)
        last-activity (atom (System/currentTimeMillis))
        envs (atom {})
        reg (registry/new-registry)
        calls (atom 0)
        provider (-> (HttpServletStreamableServerTransportProvider/builder)
                     (.mcpEndpoint "/mcp")
                     (.contextExtractor (context-extractor envs))
                     (.build))
        ^McpAsyncServer mcp-server (-> (McpServer/async provider)
                                       (.serverInfo "clojure-mcp" version)
                                       (.capabilities (-> (McpSchema$ServerCapabilities/builder) (.tools true) (.build)))
                                       (.build))
        _ (doseq [t (tool-maps env agent-model-fallback)]
            (core/add-tool mcp-server (assoc t :tool-fn (dispatcher reg (:name t) calls env agent-model-fallback))))
        server (Server.)
        connector (doto (ServerConnector. server)
                    (.setHost "127.0.0.1")
                    (.setPort 0)
                    (.setIdleTimeout 600000))
        ctx (doto (ServletContextHandler. ServletContextHandler/NO_SESSIONS)
              (.setContextPath "/")
              (.addFilter (FilterHolder. ^Filter (security-filter token port last-activity))
                          "/*" (EnumSet/of DispatcherType/REQUEST))
              (.addServlet (ServletHolder. ^HttpServlet (json-servlet (fn [_] [200 {"version" version "pid" (.pid (java.lang.ProcessHandle/current))}])))
                           "/health")
              (.addServlet (ServletHolder. ^HttpServlet (json-servlet (env-handler envs))) "/env")
              (.addServlet (doto (ServletHolder. ^HttpServlet provider) (.setAsyncSupported true)) "/p/*"))
        stopped (promise)
        ^ScheduledExecutorService scheduler (Executors/newSingleThreadScheduledExecutor)
        stop! (fn []
                (when-not (realized? stopped)
                  (state/clear-port! state-dir @port)
                  (.shutdownNow scheduler)
                  (try (.block (.closeGracefully mcp-server) (Duration/ofSeconds 3))
                       (catch Exception e (log/warn e "MCP server did not close cleanly")))
                  (.stop server)
                  (.close (.channel ^java.nio.channels.FileLock lock))
                  (deliver stopped true)))]
    (.addConnector server connector)
    (.setHandler server ctx)
    (.start server)
    (deliver port (.getLocalPort connector))
    (reset! last-activity (System/currentTimeMillis))
    (state/write-ready! state-dir {:port @port :pid (.pid (java.lang.ProcessHandle/current))})
    (let [every (max 50 (min 60000 (quot idle-exit-ms 4)))]
      (.scheduleAtFixedRate scheduler
                            (fn []
                              (when (> (- (System/currentTimeMillis) @last-activity) idle-exit-ms)
                                (log/info "Idle for" idle-exit-ms "ms, exiting")
                                (future (stop!))))
                            every every TimeUnit/MILLISECONDS))
    (log/info "clojure-mcp daemon" version "listening on 127.0.0.1:" @port)
    {:port @port :token token :stop! stop! :stopped stopped}))
