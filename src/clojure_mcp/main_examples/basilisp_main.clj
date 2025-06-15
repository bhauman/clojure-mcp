(ns clojure-mcp.main-examples.basilisp-main
  (:require
   [clojure-mcp.core :as core]
   [clojure-mcp.config :as config]
   [clojure-mcp.nrepl :as nrepl]
   [clojure.tools.logging :as log]
   [clojure-mcp.main :as main]
   [clojure-mcp.tools.eval.tool :as eval-tool]))

(def tool-name "basilisp_eval")

(def description
  "Takes a Basilisp Expression and evaluates it in the current namespace. For example, providing `(+ 1 2)` will evaluate to 3.

**Project File Access**: Can load and use any Basilisp file from your project with `(require '[your-namespace.core :as core] :reload)`. Always use `:reload` to ensure you get the latest version of files. Access functions, examine state with `@your-atom`, and manipulate application data for debugging and testing.

**Important**: Both `require` and `ns` `:require` clauses can only reference actual files from your project, not namespaces created in the same REPL session.

Python interop is fully supported including `(py/print \"hello\")`, `(py/len [1 2 3])`, Python library imports, etc.

**IMPORTANT**: This repl is intended for BASILISP CODE only.")

(defn start-basilisp-repl [nrepl-client-atom basilisp-session {:keys [basilisp-build basilisp-watch]}]
  (let [build-keyword (when basilisp-build (keyword (name basilisp-build)))
        start-code (format
                    ;; TODO we need to check if its already running
                    ;; here and only initialize if it isn't
                    (if basilisp-watch
                      "(do (basilisp/watch %s) (basilisp/repl %s))"
                      "(do (basilisp/repl %s) %s)")
                    (pr-str build-keyword)
                    (pr-str build-keyword))]
    (nrepl/eval-code-msg
     @nrepl-client-atom start-code {:session basilisp-session}
     (->> identity
          (nrepl/out-err #(log/info %) #(log/info %))
          (nrepl/value #(log/info %))
          (nrepl/done (fn [_] (log/info "done")))
          (nrepl/error (fn [args]
                         (log/info (pr-str args))
                         (log/info "ERROR in basilisp start")))))
    basilisp-session))

;; when having a completely different connection for basilisp
(defn basilisp-eval-tool-secondary-connection-tool [nrepl-client-atom {:keys [basilisp-port basilisp-build basilisp-watch] :as config}]
  (let [basilisp-nrepl-client-map (core/create-additional-connection nrepl-client-atom {:port basilisp-port})
        basilisp-nrepl-client-atom (atom basilisp-nrepl-client-map)]
    (start-basilisp-repl
     basilisp-nrepl-client-atom
     (nrepl/eval-session basilisp-nrepl-client-map)
     config)
    (-> (eval-tool/eval-code basilisp-nrepl-client-atom)
        (assoc :name tool-name)
        (assoc :description description))))

;; when sharing the clojure and basilisp repl
(defn basilisp-eval-tool [nrepl-client-atom {:keys [basilisp-build basilisp-watch] :as config}]
  (let [basilisp-session (nrepl/new-session @nrepl-client-atom)
        _ (start-basilisp-repl nrepl-client-atom basilisp-session config)]
    (-> (eval-tool/eval-code nrepl-client-atom {:nrepl-session basilisp-session})
        (assoc :name tool-name)
        (assoc :description description))))

;; So we can set up basilisp two ways
;; 1. as a single repl connection using the basilisp clojure connection for cloj eval
;; 2. or the user starts two processes one for clojure and then we connect to basilisp
;;    as a secondary connection

(defn my-tools [nrepl-client-atom {:keys [port basilisp-port basilisp-build basilisp-watch] :as config}]
  (if (and port basilisp-port (not= port basilisp-port))
    (conj (main/my-tools nrepl-client-atom)
          (basilisp-eval-tool-secondary-connection-tool nrepl-client-atom config))
    (conj (main/my-tools nrepl-client-atom)
          (basilisp-eval-tool nrepl-client-atom config))))

;; not sure if this is even needed
(def nrepl-client-atom (atom nil))

;; start the server
(defn start-mcp-server [nrepl-args]
  ;; the nrepl-args are a map with :port :host :basilisp-build
  (let [nrepl-client-map (core/create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        resources (main/my-resources nrepl-client-map working-dir)
        _ (reset! nrepl-client-atom nrepl-client-map)
        tools (my-tools nrepl-client-atom nrepl-args)
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
