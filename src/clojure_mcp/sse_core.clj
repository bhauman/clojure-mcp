(ns clojure-mcp.sse-core
  (:require
   [clojure-mcp.core :as core]
   [clojure-mcp.config :as config]
   [clojure-mcp.nrepl-launcher :as nrepl-launcher]
   [taoensso.timbre :as log])
  (:import
   [io.modelcontextprotocol.server.transport
    HttpServletSseServerTransportProvider]
   [org.eclipse.jetty.server Server]
   [org.eclipse.jetty.servlet ServletContextHandler ServletHolder]
   #_[jakarta.servlet.http HttpServlet HttpServletRequest HttpServletResponse]
   [io.modelcontextprotocol.server McpServer
    #_McpServerFeatures
    #_McpServerFeatures$AsyncToolSpecification
    #_McpServerFeatures$AsyncResourceSpecification]
   [io.modelcontextprotocol.spec
    McpSchema$ServerCapabilities]
   [com.fasterxml.jackson.databind ObjectMapper]))

;; helpers for setting up an sse mcp server

(defn mcp-sse-server []
  (log/info "Starting SSE MCP server")
  (try
    (let [transport-provider (HttpServletSseServerTransportProvider. (ObjectMapper.) "/mcp/message")
          server (-> (McpServer/async transport-provider)
                     (.serverInfo "clojure-server" "0.1.0")
                     (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                        (.tools true)
                                        (.prompts true)
                                        (.resources true true)
                                        #_(.logging)
                                        (.build)))
                     (.build))]
      (log/info "SSE MCP server initialized successfully")
      {:provider-servlet transport-provider
       :mcp-server server})
    (catch Exception e
      (log/error e "Failed to initialize SSE MCP server")
      (throw e))))

(defn host-mcp-servlet
  "Main function to start the embedded Jetty server."
  [servlet port]
  (let [server (Server. port)
        context (ServletContextHandler. ServletContextHandler/SESSIONS)]
    (.setContextPath context "/")
    (.addServlet context (ServletHolder. servlet) "/")
    (.setHandler server context)
    (.start server)
    (println (str "Clojure tooling SSE MCP server started on port " port "."))
    (.join server)))

(defn build-and-start-mcp-server-impl
  "Internal implementation of MCP server with SSE transport.
   
   Similar to core/build-and-start-mcp-server-impl but uses SSE transport
   instead of stdio, allowing web-based clients to connect over HTTP.
   
   Args:
   - nrepl-args: Map with connection settings
     - :port (required) - nREPL server port
     - :host (optional) - nREPL server host (defaults to localhost)
     - :mcp-sse-port (optional) - HTTP port for SSE server (defaults to 8078)
   
   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts  
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources
   
   All factory functions are optional. If not provided, that category won't be populated.
   
   Side effects:
   - Stores the nREPL client in core/nrepl-client-atom
   - Starts the MCP server with SSE transport
   - Starts a Jetty HTTP server on the specified port
   
   Returns: nil"
  [nrepl-args component-factories]
  ;; Note: validation should be done by caller
  (let [_ (assert (:port nrepl-args) "Port must be provided for build-and-start-mcp-server-impl")
        mcp-port (:mcp-sse-port nrepl-args 8078)
        nrepl-client-map (core/create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        ;; Store nREPL process (if auto-started) in client map for cleanup
        nrepl-client-with-process (if-let [process (:nrepl-process nrepl-args)]
                                    (assoc nrepl-client-map :nrepl-process process)
                                    nrepl-client-map)
        _ (reset! core/nrepl-client-atom nrepl-client-with-process)
        {:keys [mcp-server provider-servlet]}
        (core/setup-mcp-server core/nrepl-client-atom working-dir component-factories mcp-sse-server)]
    ;; hold onto this so you can shut it down if necessary
    (swap! core/nrepl-client-atom assoc :mcp-server mcp-server)
    ;; Start the HTTP server with the servlet
    (host-mcp-servlet provider-servlet mcp-port)
    nil))

(defn build-and-start-mcp-server
  "Builds and starts an MCP server with SSE transport and optional automatic nREPL startup.
   
   This function wraps build-and-start-mcp-server-impl with nREPL auto-start capability.
   
   If auto-start conditions are met (see nrepl-launcher/should-start-nrepl?), it will:
   1. Start an nREPL server process using :start-nrepl-cmd
   2. Parse the port from process output (if no :port provided)
   3. Pass the discovered port to the main MCP server setup
   
   Otherwise, it requires a :port parameter.
   
   Args:
   - nrepl-args: Map with connection settings and optional nREPL start configuration
     - :port (required if not auto-starting) - nREPL server port
       When provided with :start-nrepl-cmd, uses fixed port instead of parsing
     - :host (optional) - nREPL server host (defaults to localhost)
     - :mcp-sse-port (optional) - HTTP port for SSE server (defaults to 8078)
     - :project-dir (optional) - Root directory for the project
     - :start-nrepl-cmd (optional) - Command to start nREPL server
   
   - component-factories: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts  
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources
   
   Auto-start conditions (must satisfy ONE):
   1. Both :start-nrepl-cmd AND :project-dir provided in nrepl-args
   2. Current directory contains .clojure-mcp/config.edn with :start-nrepl-cmd
   
   Returns: nil"
  [nrepl-args component-factories]
  (-> nrepl-args
      core/validate-options
      nrepl-launcher/maybe-start-nrepl-process
      core/ensure-port
      (build-and-start-mcp-server-impl component-factories)))


