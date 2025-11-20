(ns clojure-mcp.main-examples.figwheel-main
  "Example of a custom MCP server that adds ClojureScript evaluation via Figwheel Main.
   
   This demonstrates the new pattern for creating custom MCP servers:
   1. Define a make-tools function that extends the base tools
   2. Call core/build-and-start-mcp-server with factory functions
   3. Reuse the standard make-prompts and make-resources from main
   
   Note: Piggieback must be configured in your nREPL middleware for this to work.
   See the comments below for the required deps.edn configuration."
  (:require
   [clojure-mcp.core :as core]
   [clojure-mcp.logging :as logging]
   [clojure-mcp.main :as main]
   [clojure-mcp.tools.figwheel.tool :as figwheel-tool]))

;; This along with `clojure-mcp.tools.figwheel.tool` are proof of
;; concept of a clojurescript_tool.  This proof of concept can be
;; improved and provides a blueprint for creating other piggieback repls
;; node, cljs.main etc.

;; Shadow is different in that it has its own nrepl connection.

;; In the figwheel based clojurescript project piggieback needs to be
;; configured in the nrepl that clojure-mcp connects to
;;
;; :aliases {:nrepl {:extra-deps {cider/piggieback {:mvn/version "0.6.0"}
;;                                nrepl/nrepl {:mvn/version "1.3.1"}
;;                                com.bhauman/figwheel-main {:mvn/version "0.2.20"}}
;;                   :extra-paths ["test" "target"] ;; examples
;;                   :jvm-opts ["-Djdk.attach.allowAttachSelf"]
;;                   :main-opts ["-m" "nrepl.cmdline" "--port" "7888"
;;                               "--middleware" "[cider.piggieback/wrap-cljs-repl]"]}}

(defn make-tools [nrepl-client-atom working-directory & [{figwheel-build :figwheel-build}]]
  (conj (main/make-tools nrepl-client-atom working-directory)
        (figwheel-tool/figwheel-eval nrepl-client-atom {:figwheel-build (or figwheel-build "dev")})))

(defn start-mcp-server [opts]
  ;; Configure logging before starting the server
  (logging/configure-logging!
   {:log-file (get opts :log-file logging/default-log-file)
    :enable-logging? (get opts :enable-logging? false)
    :log-level (get opts :log-level :debug)})
  (core/build-and-start-mcp-server
   (dissoc opts :log-file :log-level :enable-logging?)
   {:make-tools-fn (fn [nrepl-client-atom working-directory]
                     (make-tools nrepl-client-atom working-directory opts))
    :make-prompts-fn main/make-prompts
    :make-resources-fn main/make-resources}))
