(ns clojure-mcp.main-examples.figwheel-main
  (:require
   [clojure-mcp.core :as core]
   [clojure-mcp.config :as config]
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

(defn my-tools [nrepl-client-atom figwheel-build]
  (conj (main/my-tools nrepl-client-atom)
        (figwheel-tool/figwheel-eval nrepl-client-atom {:figwheel-build figwheel-build})))

;; not sure if this is even needed
(def nrepl-client-atom (atom nil))

;; start the server
(defn start-mcp-server [nrepl-args]
  ;; the nrepl-args are a map with :port :host :figwheel-build
  ;; can also include :user-dir-override for Docker setups
  (let [nrepl-client-map (core/create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        resources (main/my-resources nrepl-client-map working-dir)
        _ (reset! nrepl-client-atom nrepl-client-map)
        tools (my-tools nrepl-client-atom (:figwheel-build nrepl-args "dev"))
        prompts (main/my-prompts working-dir)
        mcp (core/mcp-server)]
    (doseq [tool tools]
      (core/add-tool mcp tool))
    (doseq [resource resources]
      (core/add-resource mcp resource))
    (doseq [prompt prompts]
      (core/add-prompt mcp prompt))
    (swap! nrepl-client-atom assoc :mcp-server mcp)
    nil))
