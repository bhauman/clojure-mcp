(ns clojure-mcp.tools
  "Tool construction functions for Clojure MCP.
   Uses dynamic symbol resolution to avoid circular dependencies."
  (:require [clojure-mcp.config :as config]))

;; Tool creation function symbols organized by category
(def read-only-tool-syms
  "Symbols for read-only tool creation functions"
  ['clojure-mcp.tools.unified-read-file.tool/unified-read-file-tool
   'clojure-mcp.tools.grep.tool/grep-tool
   'clojure-mcp.tools.glob-files.tool/glob-files-tool
   'clojure-mcp.tools.project.tool/inspect-project-tool
   'clojure-mcp.tools.nrepl-ports.tool/list-nrepl-ports-tool])

(def eval-tool-syms
  "Symbols for evaluation tool creation functions"
  ['clojure-mcp.tools.eval.tool/eval-code
   'clojure-mcp.tools.bash.tool/bash-tool])

(def editing-tool-syms
  "Symbols for file editing tool creation functions"
  ['clojure-mcp.tools.form-edit.combined-edit-tool/unified-form-edit-tool
   'clojure-mcp.tools.form-edit.tool/sexp-update-tool
   'clojure-mcp.tools.file-edit.tool/file-edit-tool
   'clojure-mcp.tools.file-write.tool/file-write-tool])

(def agent-tool-syms
  "Symbols for agent tool creation functions (require API keys)"
  ['clojure-mcp.tools.agent-tool-builder.tool/create-agent-tools])

(def experimental-tool-syms
  "Symbols for experimental tool creation functions"
  ['clojure-mcp.tools.scratch-pad.tool/scratch-pad-tool])

;; Note: introspection tools are already included in read-only-tool-syms
;; This is kept for documentation purposes but not used in all-tool-syms
(def introspection-tool-syms
  "Symbols for introspection tool creation functions"
  ['clojure-mcp.tools.project.tool/inspect-project-tool])

(def all-tool-syms
  "All tool symbols combined"
  (concat read-only-tool-syms
          eval-tool-syms
          editing-tool-syms
          agent-tool-syms
          experimental-tool-syms))

;; Memoized resolution functions
(def resolve-tool-fn
  "Memoized function to resolve a tool creation function symbol"
  (memoize
   (fn [sym]
     (require (symbol (namespace sym)))
     (deref (resolve sym)))))

(defn resolve-tool-fns
  "Resolve a collection of tool function symbols"
  [syms]
  (mapv resolve-tool-fn syms))

;; Public API functions
(defn build-read-only-tools
  "Builds and returns the read-only tools that don't modify files.
   These tools are safe for exploration and analysis."
  [nrepl-client-atom]
  (let [tool-fns (resolve-tool-fns read-only-tool-syms)]
    (mapv #(% nrepl-client-atom) tool-fns)))

(defn apply-tools-config
  "Applies :tools-config overrides to built tools.

   Currently supports overriding :description for any tool.
   Tool config is looked up by tool :id (keyword).

   Example config:
   {:tools-config {:clojure_eval {:description \"Custom eval description\"}
                   :bash {:description \"Custom bash description\"}}}

   Args:
   - nrepl-client-atom: Atom containing nREPL client with config
   - tools: Vector of built tool registration maps

   Returns: Vector of tools with config overrides applied"
  [nrepl-client-atom tools]
  (let [tools-config (config/get-tools-config @nrepl-client-atom)]
    (if (empty? tools-config)
      tools
      (mapv (fn [tool]
              (let [tool-id (:id tool)
                    tool-cfg (get tools-config tool-id)]
                (if-let [desc (:description tool-cfg)]
                  (assoc tool :description desc)
                  tool)))
            tools))))

(defn build-all-tools
  "Builds and returns all available tools including read-only, eval, editing, and agent tools.
   Note: Agent tools require API keys to be configured.
   Some tool builders may return multiple tools (e.g., agent-tool-builder).

   Tool descriptions can be overridden via :tools-config in config.edn:
   {:tools-config {:clojure_eval {:description \"Custom description\"}}}"
  [nrepl-client-atom]
  (let [tool-fns (resolve-tool-fns all-tool-syms)
        tools (vec (flatten (map (fn [f]
                                   (let [result (f nrepl-client-atom)]
                                     ;; Handle both single tools and vectors of tools
                                     (if (vector? result) result [result])))
                                 tool-fns)))]
    (apply-tools-config nrepl-client-atom tools)))

;; Category-specific builders for fine-grained control
(defn build-eval-tools
  "Build just the evaluation tools"
  [nrepl-client-atom]
  (let [tool-fns (resolve-tool-fns eval-tool-syms)]
    (mapv #(% nrepl-client-atom) tool-fns)))

(defn build-editing-tools
  "Build just the editing tools"
  [nrepl-client-atom]
  (let [tool-fns (resolve-tool-fns editing-tool-syms)]
    (mapv #(% nrepl-client-atom) tool-fns)))

(defn build-agent-tools
  "Build just the agent tools (require API keys).
   Some agent tool builders may return multiple tools (e.g., agent-tool-builder)."
  [nrepl-client-atom]
  (let [tool-fns (resolve-tool-fns agent-tool-syms)]
    (vec (flatten (map (fn [f]
                         (let [result (f nrepl-client-atom)]
                           ;; Handle both single tools and vectors of tools
                           (if (vector? result) result [result])))
                       tool-fns)))))

(defn build-experimental-tools
  "Build just the experimental tools"
  [nrepl-client-atom]
  (let [tool-fns (resolve-tool-fns experimental-tool-syms)]
    (mapv #(% nrepl-client-atom) tool-fns)))

(defn build-custom-tools
  "Build a custom set of tools from provided symbols.
   
   Example:
   (build-custom-tools nrepl-client-atom
     ['clojure-mcp.tools.eval.tool/eval-code
      'clojure-mcp.tools.file-write.tool/file-write-tool])"
  [nrepl-client-atom tool-syms]
  (let [tool-fns (resolve-tool-fns tool-syms)]
    (mapv #(% nrepl-client-atom) tool-fns)))

(defn filter-tools
  "Filters tools based on enable/disable lists.
   
   Args:
   - all-tools: Vector of all available tools
   - enable-tools: Can be:
                   - nil: returns empty vector (no tools)
                   - :all: returns all tools (minus disabled)
                   - [...]: returns only specified tools (minus disabled)
   - disable-tools: List of tool IDs to disable
   
   Returns: Filtered vector of tools"
  [all-tools enable-tools disable-tools]
  (cond
    ;; nil means no tools
    (nil? enable-tools)
    []

    ;; :all means all tools (minus disabled)
    (= enable-tools :all)
    (let [disable-set (when disable-tools
                        (set (map keyword disable-tools)))
          get-tool-id (fn [tool]
                        (or (:tool-type tool)
                            (keyword (:name tool))))]
      (filterv
       (fn [tool]
         (let [tool-id (get-tool-id tool)]
           (not (contains? disable-set tool-id))))
       all-tools))

    ;; Otherwise it's a list of specific tools
    :else
    (let [enable-set (set (map keyword enable-tools))
          disable-set (when disable-tools
                        (set (map keyword disable-tools)))
          get-tool-id (fn [tool]
                        (or (:tool-type tool)
                            (keyword (:name tool))))]
      (filterv
       (fn [tool]
         (let [tool-id (get-tool-id tool)]
           (and (contains? enable-set tool-id)
                (not (contains? disable-set tool-id)))))
       all-tools))))
