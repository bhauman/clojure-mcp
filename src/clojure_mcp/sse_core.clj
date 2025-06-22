(ns clojure-mcp.sse-core
  (:require
   [clojure-mcp.main :as main]
   [clojure-mcp.core :as core]
   [clojure-mcp.config :as config]
   [clojure.tools.logging :as log])
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

(defn build-and-start-mcp-server [args {:keys [make-tools-fn
                                               make-resources-fn
                                               make-prompts-fn]}]
  ;; the args are a map with :port :host
  ;; we also need an :mcp-sse-port so we'll default to 8078??
  (let [mcp-port (:mcp-sse-port args 8078)
        nrepl-client-map (core/create-and-start-nrepl-connection args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        _ (reset! core/nrepl-client-atom nrepl-client-map)
        resources (when make-resources-fn
                    (doall (make-resources-fn
                            core/nrepl-client-atom working-dir)))
        tools (when make-tools-fn
                (doall (make-tools-fn
                        core/nrepl-client-atom working-dir)))
        prompts (when make-prompts-fn
                  (doall (make-prompts-fn
                          core/nrepl-client-atom working-dir)))
        {:keys [mcp-server provider-servlet]} (mcp-sse-server)]
    (doseq [tool tools]
      (core/add-tool mcp-server tool))
    (doseq [resource resources]
      (core/add-resource mcp-server resource))
    (doseq [prompt prompts]
      (core/add-prompt mcp-server prompt))
    ;; hold onto this so you can shut it down if necessary
    (swap! core/nrepl-client-atom assoc :mcp-server mcp-server)
    (host-mcp-servlet provider-servlet mcp-port)
    nil))


