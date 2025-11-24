(ns clojure-mcp.main-examples.shadow-main
  "Example of a custom MCP server that adds ClojureScript evaluation via Shadow CLJS.
   
   This demonstrates the new pattern for creating custom MCP servers:
   1. Define a make-tools function that extends the base tools
   2. Optionally define make-prompts and make-resources functions
   3. Call core/build-and-start-mcp-server with factory functions
   
   Shadow CLJS support can work in two modes:
   - Single connection: Share the Clojure nREPL for both CLJ and CLJS
   - Dual connection: Connect to a separate Shadow CLJS nREPL server"
  (:require
   [clojure-mcp.core :as core]
   [clojure-mcp.logging :as logging]
   [clojure-mcp.nrepl :as nrepl]
   [taoensso.timbre :as log]
   [clojure-mcp.main :as main]
   [clojure-mcp.tools.eval.tool :as eval-tool]))

(def tool-name "clojurescript_eval")

(def description
  "Takes a ClojureScript Expression and evaluates it in the current namespace. For example, providing `(+ 1 2)` will evaluate to 3.

**Project File Access**: Can load and use any ClojureScript file from your project with `(require '[your-namespace.core :as core] :reload)`. Always use `:reload` to ensure you get the latest version of files. Access functions, examine state with `@your-atom`, and manipulate application data for debugging and testing. 

**Important**: Both `require` and `ns` `:require` clauses can only reference actual files from your project, not namespaces created in the same REPL session.

JavaScript interop is fully supported including `js/console.log`, `js/setTimeout`, DOM APIs, etc.

**IMPORTANT**: This repl is intended for CLOJURESCRIPT CODE only.")

(defn start-shadow-repl [nrepl-client-atom {:keys [shadow-build shadow-watch]}]
  (let [start-code (format
                    ;; TODO we need to check if its already running
                    ;; here and only initialize if it isn't
                    (if shadow-watch
                      "(do (shadow/watch %s) (shadow/repl %s))"
                      "(do (shadow/repl %s) %s)")
                    (pr-str (keyword (name shadow-build)))
                    (pr-str (keyword (name shadow-build))))]
    (log/info "Starting Shadow CLJS...")
    (try
      (nrepl/eval-code @nrepl-client-atom start-code :session-type :shadow)
      (log/info "Shadow CLJS started (or command sent)")
      (catch Exception e
        (log/error e "ERROR in shadow start")))
    :shadow))

;; when having a completely different connection for cljs
(defn shadow-eval-tool-secondary-connection-tool [nrepl-client-atom {:keys [shadow-port _shadow-build _shadow-watch] :as config}]
  (let [cljs-nrepl-client-map (core/create-additional-connection nrepl-client-atom {:port shadow-port})
        cljs-nrepl-client-atom (atom cljs-nrepl-client-map)]
    (start-shadow-repl
     cljs-nrepl-client-atom
     config)
    (-> (eval-tool/eval-code cljs-nrepl-client-atom {:session-type :shadow})
        (assoc :name tool-name)
        (assoc :id (keyword tool-name))
        (assoc :description description))))

;; when sharing the clojure and cljs repl
(defn shadow-eval-tool [nrepl-client-atom {:keys [_shadow-build _shadow-watch] :as config}]
  (start-shadow-repl nrepl-client-atom config)
  (-> (eval-tool/eval-code nrepl-client-atom {:session-type :shadow})
      (assoc :name tool-name)
      (assoc :id (keyword tool-name))
      (assoc :description description)))

;; So we can set up shadow two ways
;; 1. as a single repl connection using the shadow clojure connection for cloj eval
;; 2. or the user starts two processes one for clojure and then we connect to shadow
;;    as a secondary connection

(defn make-tools [nrepl-client-atom working-directory & [{:keys [port shadow-port _shadow-build _shadow-watch] :as config}]]
  (if (and port shadow-port (not= port shadow-port))
    (conj (main/make-tools nrepl-client-atom working-directory)
          (shadow-eval-tool-secondary-connection-tool nrepl-client-atom config))
    (conj (main/make-tools nrepl-client-atom working-directory)
          (shadow-eval-tool nrepl-client-atom config))))

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
