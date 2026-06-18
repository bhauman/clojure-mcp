(ns clojure-mcp.streamable-http-main
  "Example of a custom MCP server using the Streamable HTTP transport.

   This demonstrates reusing the standard tools, prompts, and resources
   from main.clj while using a different transport mechanism (Streamable
   HTTP instead of stdio). The Streamable HTTP transport allows web-based
   clients to connect over a single HTTP endpoint."
  (:require
   [clojure-mcp.logging :as logging]
   [clojure-mcp.main :as main]
   [clojure-mcp.streamable-http-core :as streamable-http-core]))

(defn start-streamable-http-mcp-server [opts]
  ;; Configure logging before starting the server
  (logging/configure-logging!
   {:log-file (get opts :log-file logging/default-log-file)
    :enable-logging? (get opts :enable-logging? false)
    :log-level (get opts :log-level :debug)})
  (streamable-http-core/build-and-start-mcp-server
   (dissoc opts :log-file :log-level :enable-logging?)
   {:make-tools-fn main/make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))

(defn start
  "Entry point for running the Streamable HTTP server from a project directory.

   Sets :project-dir to current working directory unless :not-cwd is true.
   This allows running without an immediate REPL connection - REPL initialization
   happens lazily when first needed.

   Options:
   - :not-cwd - If true, does NOT set project-dir to cwd (default: false)
   - :port - Optional nREPL port (REPL is optional when project-dir is set)
   - :mcp-http-port - HTTP port for the Streamable HTTP server (required)
   - All other options supported by start-streamable-http-mcp-server"
  [opts]
  (let [not-cwd? (get opts :not-cwd false)
        opts' (if not-cwd?
                opts
                (assoc opts :project-dir (System/getProperty "user.dir")))]
    (start-streamable-http-mcp-server opts')))
