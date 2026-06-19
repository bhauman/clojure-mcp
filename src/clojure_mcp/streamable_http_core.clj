(ns clojure-mcp.streamable-http-core
  (:require
   [clojure-mcp.core :as core]
   [clojure-mcp.config :as config]
   [clojure-mcp.nrepl-launcher :as nrepl-launcher]
   [taoensso.timbre :as log])
  (:import
   [io.modelcontextprotocol.server.transport
    HttpServletStreamableServerTransportProvider]
   [org.eclipse.jetty.server Server]
   [org.eclipse.jetty.servlet ServletContextHandler ServletHolder]
   #_[jakarta.servlet.http HttpServlet HttpServletRequest HttpServletResponse]
   [io.modelcontextprotocol.server McpServer
    #_McpServerFeatures
    #_McpServerFeatures$AsyncToolSpecification
    #_McpServerFeatures$AsyncResourceSpecification]
   [io.modelcontextprotocol.spec
    McpSchema$ServerCapabilities]))

;; helpers for setting up a Streamable HTTP mcp server

(def ^:const default-mcp-endpoint
  "Single HTTP endpoint that handles the Streamable HTTP transport
   (GET for the server->client SSE stream, POST for client messages,
   DELETE for session termination)."
  "/mcp")

(defn mcp-streamable-http-server
  "Creates an MCP server using the Streamable HTTP transport.

   The Streamable HTTP transport is the successor to the legacy HTTP+SSE
   transport: a single endpoint multiplexes client messages (POST), the
   server->client stream (GET, upgraded to SSE on demand), and session
   termination (DELETE).

   Optional `instructions` is a string advertised to the client at
   initialization; nil omits the instructions field."
  ([] (mcp-streamable-http-server nil))
  ([instructions]
   (log/info "Starting Streamable HTTP MCP server")
   (try
     (let [transport-provider (-> (HttpServletStreamableServerTransportProvider/builder)
                                  (.mcpEndpoint default-mcp-endpoint)
                                  (.build))
           server (cond-> (-> (McpServer/async transport-provider)
                              (.serverInfo "clojure-server" "0.1.0")
                              (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                                 (.tools true)
                                                 (.prompts true)
                                                 (.resources true true)
                                                 #_(.logging)
                                                 (.build))))
                    (some? instructions) (.instructions instructions)
                    :always (.build))]
       (log/info "Streamable HTTP MCP server initialized successfully")
       {:provider-servlet transport-provider
        :mcp-server server})
     (catch Exception e
       (log/error e "Failed to initialize Streamable HTTP MCP server")
       (throw e)))))

(defn host-mcp-servlet
  "Main function to start the embedded Jetty server."
  [servlet port]
  (let [server (Server. port)
        context (ServletContextHandler. ServletContextHandler/SESSIONS)]
    (.setContextPath context "/")
    (.addServlet context (ServletHolder. servlet) "/")
    (.setHandler server context)
    (.start server)
    (println (str "Clojure tooling Streamable HTTP MCP server started on port " port
                  " (endpoint " default-mcp-endpoint ")."))
    (.join server)))

(defn build-and-start-mcp-server-impl
  "Internal implementation of MCP server with Streamable HTTP transport.

   Similar to core/build-and-start-mcp-server-impl but uses the Streamable
   HTTP transport instead of stdio, allowing web-based clients to connect
   over HTTP.

   Like the stdio transport, the nREPL connection is optional at startup: when
   :project-dir is provided the server starts without a REPL and connects lazily
   on the first eval (and can target other nREPLs by port at runtime). A :port is
   only required when :project-dir is not provided.

   Args:
   - nrepl-args: Map with connection settings
     - :port (required if no :project-dir) - nREPL server port
     - :host (optional) - nREPL server host (defaults to localhost)
     - :project-dir (optional) - Root directory for the project. If provided, port is optional.
     - :mcp-http-port (optional) - HTTP port for the server (defaults to 8078)

   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources

   All factory functions are optional. If not provided, that category won't be populated.

   Side effects:
   - Stores the nREPL client in core/nrepl-client-atom
   - Starts the MCP server with Streamable HTTP transport
   - Starts a Jetty HTTP server on the specified port

   Returns: nil"
  [nrepl-args component-factories]
  ;; Either :port or :project-dir must be provided (validated by caller via
  ;; core/ensure-port-if-needed); without a port the REPL connects lazily.
  (let [mcp-port (:mcp-http-port nrepl-args 8078)
        nrepl-client-map (core/create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        ;; Store nREPL process (if auto-started) in client map for cleanup
        nrepl-client-with-process (if-let [process (:nrepl-process nrepl-args)]
                                    (assoc nrepl-client-map :nrepl-process process)
                                    nrepl-client-map)
        _ (reset! core/nrepl-client-atom nrepl-client-with-process)
        {:keys [mcp-server provider-servlet]}
        (core/setup-mcp-server core/nrepl-client-atom working-dir component-factories
                               #(mcp-streamable-http-server (config/get-mcp-instructions @core/nrepl-client-atom)))]
    ;; hold onto this so you can shut it down if necessary
    (swap! core/nrepl-client-atom assoc :mcp-server mcp-server)
    ;; Start the HTTP server with the servlet
    (host-mcp-servlet provider-servlet mcp-port)
    nil))

(defn build-and-start-mcp-server
  "Builds and starts an MCP server with Streamable HTTP transport and optional automatic nREPL startup.

   This function wraps build-and-start-mcp-server-impl with nREPL auto-start capability.

   If auto-start conditions are met (see nrepl-launcher/should-start-nrepl?), it will:
   1. Start an nREPL server process using :start-nrepl-cmd
   2. Parse the port from process output (if no :port provided)
   3. Pass the discovered port to the main MCP server setup

   Port is only required when :project-dir is NOT provided. When :project-dir is
   given, the server starts without an nREPL connection and connects lazily on
   first use (mirroring the stdio transport).

   Args:
   - nrepl-args: Map with connection settings and optional nREPL start configuration
     - :port (required if no :project-dir and not auto-starting) - nREPL server port
       When provided with :start-nrepl-cmd, uses fixed port instead of parsing
     - :host (optional) - nREPL server host (defaults to localhost)
     - :mcp-http-port (optional) - HTTP port for the server (defaults to 8078)
     - :project-dir (optional) - Root directory for the project. If provided, port is optional.
     - :start-nrepl-cmd (optional) - Command to start nREPL server (always
       starts a fresh nREPL; ignored when :fallback-nrepl is also set)
     - :fallback-nrepl (optional) - When true, attempts to attach to
       :port first; if unreachable (or no :port given), spawns a local
       nREPL on an ephemeral port. Does not collide with the configured
       :port. See clojure-mcp.nrepl-launcher/maybe-start-fallback-nrepl.
     - :fallback-nrepl-cmd (optional) - Override the default fallback
       command. Vector of strings. Default uses `clojure` with nREPL
       pulled in via -Sdeps.
     - :fallback-nrepl-dir (optional) - Working directory for the
       spawned fallback REPL. Default: :project-dir if set, else $HOME.

   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources

   Auto-start conditions (must satisfy ONE):
   1. Both :start-nrepl-cmd AND :project-dir provided in nrepl-args
   2. Current directory contains .clojure-mcp/config.edn with :start-nrepl-cmd
   3. :fallback-nrepl is true (handled separately by the fallback launcher)

   Returns: nil"
  [nrepl-args component-factories]
  (-> nrepl-args
      core/validate-options
      nrepl-launcher/maybe-start-fallback-nrepl
      nrepl-launcher/maybe-start-nrepl-process
      core/ensure-port-if-needed
      (build-and-start-mcp-server-impl component-factories)))
