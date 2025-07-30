(ns clojure-mcp.tools.eval.tool
  "Implementation of the eval tool using the tool-system multimethod approach."
  (:require
   [clojure.string :as string]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.eval.core :as core]))

;; Factory function to create the tool configuration
(defn create-eval-tool
  "Creates the evaluation tool configuration"
  ([nrepl-client-atom]
   (create-eval-tool nrepl-client-atom {}))
  ([nrepl-client-atom {:keys [nrepl-session] :as config}]
   (cond-> {:tool-type ::clojure-eval
            :nrepl-client-atom nrepl-client-atom
            :timeout 30000}
     nrepl-session (assoc :session nrepl-session))))

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
                :timeout-ms {:type :integer
                             :description "Optional timeout in milliseconds for evaluation."}}
   :required [:code]})

(defmethod tool-system/validate-inputs ::clojure-eval [_ inputs]
  (let [{:keys [code]} inputs]
    (when-not code
      (throw (ex-info (str "Missing required parameter: code " (pr-str inputs))
                      {:inputs inputs})))
    ;; Return validated inputs (could do more validation/coercion here)
    inputs))

(defmethod tool-system/execute-tool ::clojure-eval [{:keys [nrepl-client-atom session]} inputs]
  ;; Delegate to core implementation with repair
  (core/evaluate-with-repair @nrepl-client-atom (cond-> inputs
                                                  session (assoc :session session))))

(defmethod tool-system/format-results ::clojure-eval [_ {:keys [outputs error repaired] :as eval-result}]
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

(comment
  ;; === Examples of using the eval tool ===
  (require 'clojure-mcp.nrepl)

  ;; Setup for REPL-based testing
  (def client-atom (atom (clojure-mcp.nrepl/create {:port 7888})))
  (clojure-mcp.nrepl/start-polling @client-atom)

  ;; Create a tool instance
  (def eval-tool (create-eval-tool client-atom))

  ;; Test the individual multimethod steps with options map
  (def inputs {:code "(+ 1 2)"})
  (def validated (tool-system/validate-inputs eval-tool inputs))
  (def result (tool-system/execute-tool eval-tool validated))
  (def formatted (tool-system/format-results eval-tool result))

  ;; Test with ns in options map
  (def inputs-with-ns {:code "(+ 1 2)" :ns "clojure.string"})
  (def validated-with-ns (tool-system/validate-inputs eval-tool inputs-with-ns))
  (def result-with-ns (tool-system/execute-tool eval-tool validated-with-ns))
  (def formatted-with-ns (tool-system/format-results eval-tool result-with-ns))

  ;; Generate the full registration map
  (def reg-map (tool-system/registration-map eval-tool))

  ;; Test running the tool-fn directly
  (def tool-fn (:tool-fn reg-map))
  (tool-fn nil {"code" "(+ 1 2)"} (fn [result error] (println "Result:" result "Error:" error)))
  (tool-fn nil {"code" "(+ 1 2", "ns" "clojure.string"}
           (fn [result error] (println "Result:" result "Error:" error)))

  ;; Make a simpler test function that works like tool-fn
  (defn test-tool [code & [ns]]
    (let [prom (promise)
          params (cond-> {"code" code}
                   ns (assoc "ns" ns))]
      (tool-fn nil params
               (fn [result error]
                 (deliver prom (if error {:error error} {:result result}))))
      @prom))

  ;; Test with simple expressions
  (test-tool "(+ 1 2)")
  (test-tool "(println \"Hello\")\n(+ 3 4)")
  (test-tool "(/ 1 0)")

  ;; Test with ns parameter
  (test-tool "(str *ns*)")
  (test-tool "(str *ns*)" "clojure.string")
  (test-tool "join" "clojure.string") ;; Fails because 'join' isn't a complete expression
  (test-tool "(join" "clojure.string") ;; Auto-repairs to (join)

  ;; Test with auto-repairable code
  (test-tool "(defn hello [name] (println name)") ;; Missing closing paren
  (test-tool "(defn hello [name] (println name)))") ;; Extra closing paren

  ;; Test with syntax errors that cannot be repaired
  (test-tool "(defn hello [123] (println name))") ;; Invalid argument name
  (test-tool "(defn hello [name] (println \"Hello))") ;; Unclosed string

  ;; Enhanced test function that captures repaired status
  (defn test-tool-full [code & [ns]]
    (let [prom (promise)
          params (cond-> {"code" code}
                   ns (assoc "ns" ns))]
      (tool-fn nil params
               (fn [result error repaired]
                 (deliver prom {:result result
                                :error error
                                :repaired repaired})))
      @prom))

  ;; Test with the enhanced function to see repair status
  (test-tool-full "(defn hello [name] (println name)") ;; Should show repaired: true
  (test-tool-full "(+ 1 2)") ;; Should show repaired: false/nil
  (test-tool-full "(str *ns*)" "clojure.string") ;; With ns parameter

  ;; Clean up
  (clojure-mcp.nrepl/stop-polling @client-atom))
