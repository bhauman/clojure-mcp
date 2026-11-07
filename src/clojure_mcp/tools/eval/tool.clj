(ns clojure-mcp.tools.eval.tool
  "Implementation of the eval tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.eval.core :as core]
   [clojure-mcp.config :as config]
   [clojure-mcp.nrepl :as nrepl]
   [clojure-mcp.tools.eval.deadline :as deadline]
   [clojure-mcp.tools.eval.discovery :as discovery]))

;; Factory function to create the tool configuration
(defn create-eval-tool
  "Creates the evaluation tool configuration"
  ([nrepl-client-atom]
   (create-eval-tool nrepl-client-atom {}))
  ([nrepl-client-atom {:keys [session-type deadline-grace-ms] :as _config}]
   (cond-> {:tool-type ::clojure-eval
            :nrepl-client-atom nrepl-client-atom
            :timeout 20000
            :deadline-grace-ms (or deadline-grace-ms deadline/default-grace-ms)}
     session-type (assoc :session-type session-type))))

;; Implement the required multimethods for the eval tool
(defmethod tool-system/tool-annotations ::clojure-eval [_]
  {:title "Evaluate Clojure Code"
   :destructive? true})

(defmethod tool-system/tool-description ::clojure-eval [_]
  "Takes a Clojure Expression and evaluates it in the current namespace. For example, providing \"(+ 1 2)\" will evaluate to 3.

This tool is intended to execute Clojure code. This is very helpful for verifying that code is working as expected. It's also helpful for REPL driven development.

If you send multiple expressions they will all be evaluated individually and their output will be clearly partitioned.

If the returned value is too long it will be truncated.

IMPORTANT: When using `require` to reload namespaces ALWAYS use `:reload` to ensure you get the latest version of files.

PORT PARAMETER: when a project runs more than one nREPL (e.g. a Clojure JVM AND a shadow-cljs build on different ports), pass `port` to target a specific one. Without it the port comes from the project's `.nrepl-port`, which may be the wrong REPL or none. If no port resolves, the result lists the running nREPLs and how to start this project's REPL.

CLOJURESCRIPT: a shadow-cljs nREPL is a Clojure REPL until upgraded. To evaluate in a build's live runtime (browser or Node), use the `cljs_eval` tool, which reads `.shadow-cljs/nrepl.port` and needs no nested REPL. `cljs_build_status` shows compile errors; `cljs_project_state` lists builds and runtimes.

REPL helper functions are automatically loaded in the 'clj-mcp.repl-tools' namespace, providing convenient namespace and symbol exploration:

Namespace/Symbol inspection functions:
  clj-mcp.repl-tools/list-ns           - List all available namespaces
  clj-mcp.repl-tools/list-vars         - List all vars in namespace
  clj-mcp.repl-tools/doc-symbol        - Show documentation for symbol
  clj-mcp.repl-tools/source-symbol     - Show source code for symbol
  clj-mcp.repl-tools/find-symbols      - Find symbols matching pattern
  clj-mcp.repl-tools/complete          - Find completions for prefix
  clj-mcp.repl-tools/help              - Show this help message

Examples:
  (clj-mcp.repl-tools/list-ns)                     ; List all namespaces
  (clj-mcp.repl-tools/list-vars 'clojure.string)   ; List functions in clojure.string
  (clj-mcp.repl-tools/doc-symbol 'map)             ; Show documentation for map
  (clj-mcp.repl-tools/source-symbol 'map)          ; Show source code for map
  (clj-mcp.repl-tools/find-symbols \"seq\")          ; Find symbols containing \"seq\"
  (clj-mcp.repl-tools/complete \"clojure.string/j\") ; Find completions for prefix")

(defmethod tool-system/tool-schema ::clojure-eval [_]
  {:type :object
   :properties {:code {:type :string
                       :description "The Clojure code to evaluate."}
                :timeout_ms {:type :integer
                             :description "Optional timeout in milliseconds for evaluation."}
                :port {:type :integer
                       :description "Optional nREPL port to evaluate on. If not specified, uses the default port. Useful for evaluating on different nREPL servers (e.g., ClojureScript via shadow-cljs)."}}
   :required [:code]})

(defmethod tool-system/validate-inputs ::clojure-eval [{:keys [nrepl-client-atom]} inputs]
  (let [{:keys [code timeout_ms port]} inputs]
    (when-not code
      (throw (ex-info (str "Missing required parameter: code " (pr-str inputs))
                      {:inputs inputs})))
    (when (and timeout_ms (not (number? timeout_ms)))
      (throw (ex-info (str "Error parameter must be number: timeout_ms " (pr-str inputs))
                      {:inputs inputs})))
    (when (and port (not (pos-int? port)))
      (throw (ex-info (str "Error parameter must be positive integer: port " (pr-str inputs))
                      {:inputs inputs})))
    ;; Resolve effective port: provided, configured, or from .nrepl-port file.
    ;; nil makes execute-tool answer with discovery help instead of evaluating.
    (let [service @nrepl-client-atom
          project-dir (config/get-nrepl-user-dir service)
          effective-port (or port
                             (:port service)
                             (nrepl/read-nrepl-port-file project-dir))]
      (assoc inputs :port effective-port))))

(defmethod tool-system/execute-tool ::clojure-eval [{:keys [nrepl-client-atom timeout session-type deadline-grace-ms]}
                                                    {:keys [timeout_ms port] :as inputs}]
  ;; port is already resolved by validate-inputs
  (let [base-client @nrepl-client-atom
        session-type (or session-type :default)]
    (if (and (nil? port) (nil? (:port base-client)))
      (discovery/no-nrepl-help base-client (config/get-nrepl-user-dir base-client))
      (deadline/call-with-deadline
       "clojure_eval"
       (deadline/deadline-ms (or timeout_ms timeout)
                             (or deadline-grace-ms deadline/default-grace-ms))
       (fn []
         (try
           (let [client (nrepl/with-port-initialized base-client port)
                 env-type (nrepl/get-port-env-type client)
                 show-shadow-msg? (config/get-shadow-cljs-repl-message base-client)
                 ;; Check CLJS mode for shadow-cljs (skip probe if message disabled)
                 cljs-mode? (when (and (= env-type :shadow) show-shadow-msg?)
                              (nrepl/shadow-cljs-mode? client session-type))
                 ;; Execute the eval
                 eval-result (core/evaluate-with-repair client (cond-> inputs
                                                                 session-type (assoc :session-type session-type)
                                                                 (nil? timeout_ms) (assoc :timeout_ms timeout)))]
             ;; Add context to result for formatting
             (assoc eval-result :context {:env-type env-type
                                          :shadow-cljs-mode? cljs-mode?
                                          :show-shadow-message? show-shadow-msg?}))
           (catch java.net.ConnectException e
             {:outputs [[:err (format "Failed to connect to nREPL server on port %d: %s. Ensure an nREPL server is running on that port."
                                      port (.getMessage e))]]
              :error true
              :context nil})
           (catch java.net.SocketException e
             {:outputs [[:err (format "Connection error to nREPL server on port %d: %s. The server may have disconnected."
                                      port (.getMessage e))]]
              :error true
              :context nil})))))))

(defmethod tool-system/format-results ::clojure-eval [_ {:keys [outputs error repaired context] :as _eval-result}]
  ;; The core implementation returns :outputs, :error, :repaired, and :context
  ;; Pass context to formatting for namespace/env-type display in dividers
  {:result (core/partition-and-format-outputs outputs context)
   :error error
   :repaired repaired})

;; Backward compatibility function that returns the registration map
(defn eval-code
  ([nrepl-client-atom]
   (tool-system/registration-map (create-eval-tool nrepl-client-atom)))
  ([nrepl-client-atom config]
   (tool-system/registration-map (create-eval-tool nrepl-client-atom config))))