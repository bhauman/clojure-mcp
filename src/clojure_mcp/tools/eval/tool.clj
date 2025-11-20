(ns clojure-mcp.tools.eval.tool
  "Implementation of the eval tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.eval.core :as core]))

;; Factory function to create the tool configuration
(defn create-eval-tool
  "Creates the evaluation tool configuration"
  ([nrepl-client-atom]
   (create-eval-tool nrepl-client-atom {}))
  ([nrepl-client-atom {:keys [session-type] :as _config}]
   (cond-> {:tool-type ::clojure-eval
            :nrepl-client-atom nrepl-client-atom
            :timeout 20000}
     session-type (assoc :session-type session-type))))

;; Implement the required multimethods for the eval tool
(defmethod tool-system/tool-description ::clojure-eval [_]
  "Takes a Clojure Expression and evaluates it in the current namespace. For example, providing \"(+ 1 2)\" will evaluate to 3.

This tool is intended to execute Clojure code. This is very helpful for verifying that code is working as expected. It's also helpful for REPL driven development.

If you send multiple expressions they will all be evaluated individually and their output will be clearly partitioned.

If the returned value is too long it will be truncated.

IMPORTANT: When using `require` to reload namespaces ALWAYS use `:reload` to ensure you get the latest version of files.

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
                             :description "Optional timeout in milliseconds for evaluation."}}
   :required [:code]})

(defmethod tool-system/validate-inputs ::clojure-eval [_ inputs]
  (let [{:keys [code timeout_ms]} inputs]
    (when-not code
      (throw (ex-info (str "Missing required parameter: code " (pr-str inputs))
                      {:inputs inputs})))
    (when (and timeout_ms (not (number? timeout_ms)))
      (throw (ex-info (str "Error parameter must be number: timeout_ms " (pr-str inputs))
                      {:inputs inputs})))
    ;; Return validated inputs (could do more validation/coercion here)
    inputs))

(defmethod tool-system/execute-tool ::clojure-eval [{:keys [nrepl-client-atom timeout session-type]}
                                                    {:keys [timeout_ms] :as inputs}]
  ;; Delegate to core implementation with repair
  (core/evaluate-with-repair @nrepl-client-atom (cond-> inputs
                                                  session-type (assoc :session-type session-type)
                                                  (nil? timeout_ms) (assoc :timeout_ms timeout))))

(defmethod tool-system/format-results ::clojure-eval [_ {:keys [outputs error repaired] :as _eval-result}]
  ;; The core implementation now returns a map with :outputs (raw outputs), :error (boolean), and :repaired (boolean)
  ;; We need to format the outputs and return a map with :result, :error, and :repaired
  {:result (core/partition-and-format-outputs outputs)
   :error error
   :repaired repaired})

;; Backward compatibility function that returns the registration map
(defn eval-code
  ([nrepl-client-atom]
   (tool-system/registration-map (create-eval-tool nrepl-client-atom)))
  ([nrepl-client-atom config]
   (tool-system/registration-map (create-eval-tool nrepl-client-atom config))))