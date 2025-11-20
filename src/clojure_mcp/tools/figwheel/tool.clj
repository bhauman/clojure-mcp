(ns clojure-mcp.tools.figwheel.tool
  (:require
   [taoensso.timbre :as log]
   [clojure.string :as string]
   [clojure-mcp.nrepl :as nrepl]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.eval.tool :as eval-tool]
   [clojure-mcp.tools.eval.core :as eval-core]))

(defn start-figwheel [nrepl-client-atom build]
  (let [start-code (format
                    ;; TODO we need to check if its already running
                    ;; here and only initialize if it isn't
                    "(do (require (quote figwheel.main)) (figwheel.main/start %s))"
                    (pr-str build))]
    (log/info "Starting Figwheel...")
    (try
      (nrepl/eval-code @nrepl-client-atom start-code :session-type :figwheel)
      (log/info "Figwheel started (or command sent)")
      (catch Exception e
        (log/error e "ERROR in figwheel start")))
    :figwheel))

(defn create-figwheel-eval-tool
  "Creates the evaluation tool configuration"
  [nrepl-client-atom {:keys [figwheel-build] :as _config}]
  (start-figwheel nrepl-client-atom figwheel-build)
  {:tool-type ::figwheel-eval
   :nrepl-client-atom nrepl-client-atom
   :timeout 30000
   :session-type :figwheel})

;; delegate schema validate-inputs and format-results to clojure-eval
(derive ::figwheel-eval ::eval-tool/clojure-eval)

(defmethod tool-system/tool-name ::figwheel-eval [_]
  "clojurescript_eval")

(defmethod tool-system/tool-description ::figwheel-eval [_]
  "Takes a ClojureScript Expression and evaluates it in the current namespace. For example, providing `(+ 1 2)` will evaluate to 3.

**Project File Access**: Can load and use any ClojureScript file from your project with `(require '[your-namespace.core :as core] :reload)`. Always use `:reload` to ensure you get the latest version of files. Access functions, examine state with `@your-atom`, and manipulate application data for debugging and testing. 

**Important**: Both `require` and `ns` `:require` clauses can only reference actual files from your project, not namespaces created in the same REPL session.

**CRITICAL CONSTRAINT**: This ClojureScript REPL can only evaluate ONE expression. If multiple expressions are submitted the rest will be ignored. You can submit multiple expressions joined in a `(do ...)` block.

**Namespace Rules**: Namespaces must be declared at the top level separately:
```clojure
;; First evaluate namespace
(ns example.namespace)

;; Then evaluate functions  
(do 
  (defn add [a b] (+ a b))
  (add 5 9))
```

**WILL NOT WORK**:
```clojure
(do
  (ns example.namespace)
  (defn add [a b] (+ a b)))
```

JavaScript interop is fully supported including `js/console.log`, `js/setTimeout`, DOM APIs, etc.

**IMPORTANT**: This repl is intended for CLOJURESCRIPT CODE only.")

(defmethod tool-system/execute-tool ::figwheel-eval [{:keys [nrepl-client-atom session-type]} inputs]
  (assert session-type)
  (assert (:code inputs))
  ;; :code has to exist at this point
  (let [code (:code inputs (get inputs "code"))]
    ;; *ns* doesn't work on ClojureScript and its confusing for the LLM
    (if (= (string/trim code) "*ns*")
      {:outputs [[:value (nrepl/current-ns @nrepl-client-atom session-type)]]
       :error false}
      (eval-core/evaluate-with-repair @nrepl-client-atom (assoc inputs :session-type session-type)))))

;; config needs :fig
(defn figwheel-eval [nrepl-client-atom config]
  {:pre [config (:figwheel-build config)]}
  (tool-system/registration-map (create-figwheel-eval-tool nrepl-client-atom config)))
