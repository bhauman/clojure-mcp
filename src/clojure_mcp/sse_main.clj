(ns clojure-mcp.sse-main
  "Example of a custom MCP server using Server-Sent Events (SSE) transport.
   
   This demonstrates reusing the standard tools, prompts, and resources
   from main.clj while using a different transport mechanism (SSE instead
   of stdio). The SSE transport allows web-based clients to connect."
  (:require
   [clojure-mcp.main :as main]
   [clojure-mcp.sse-core :as sse-core]))

(defn start-sse-mcp-server [opts]
  (sse-core/build-and-start-mcp-server
   opts
   {:make-tools-fn main/make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))

