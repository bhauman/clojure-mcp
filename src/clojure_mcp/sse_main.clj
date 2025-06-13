(ns clojure-mcp.sse-main
  (:require
   [clojure-mcp.main :as main]
   [clojure-mcp.sse-core :as sse-core]
   [clojure-mcp.core :as core]
   [clojure-mcp.config :as config]
   [clojure.tools.logging :as log]))

(def nrepl-client-atom (atom nil))

(defn start-sse-mcp-server [args]
  ;; the nrepl-args are a map with :port :host
  ;; can also include :user-dir-override for Docker setups
  ;; we also need an :mcp-sse-port so we'll default to 8078??
  (let [mcp-port (:mcp-sse-port args 8078)
        nrepl-client-map (core/create-and-start-nrepl-connection args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        resources (main/my-resources nrepl-client-map working-dir)
        _ (reset! nrepl-client-atom nrepl-client-map)
        tools (main/my-tools nrepl-client-atom)
        prompts (main/my-prompts working-dir)
        {:keys [mcp-server provider-servlet]} (sse-core/mcp-sse-server)]
    (doseq [tool tools]
      (core/add-tool mcp-server tool))
    (doseq [resource resources]
      (core/add-resource mcp-server resource))
    (doseq [prompt prompts]
      (core/add-prompt mcp-server prompt))
    ;; hold onto this so you can shut it down if necessary
    (swap! nrepl-client-atom assoc :mcp-server mcp-server)
    (sse-core/host-mcp-servlet provider-servlet mcp-port)
    nil))
