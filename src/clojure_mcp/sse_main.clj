(ns clojure-mcp.sse-main
  (:require
   [clojure-mcp.main :as main]
   [clojure-mcp.sse-core :as sse-core]))

(defn start-sse-mcp-server [opts]
  (sse-core/build-and-start-mcp-server
   opts
   {:make-tools-fn main/make-tools
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))

