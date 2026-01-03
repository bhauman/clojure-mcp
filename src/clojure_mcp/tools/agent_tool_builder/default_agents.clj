(ns clojure-mcp.tools.agent-tool-builder.default-agents
  "Default agent configurations that replicate the functionality of
   the original hardcoded agent tools (dispatch_agent, architect, code_critique)"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn dispatch-agent-config
  "Configuration for the dispatch agent - a general purpose agent with read-only tools"
  []
  {:id :dispatch-agent
   :name "dispatch_agent"
   :description (slurp (io/resource "clojure_mcp/tools/dispatch_agent/description.md"))
   :system-message (slurp (io/resource "clojure_mcp/tools/dispatch_agent/system_message.md"))
   :context true ; Uses default code index and project summary
   :enable-tools [:LS :read_file :grep :glob_files :clojure_inspect_project]
   :memory-size 100})

(defn architect-config
  "Configuration for the architect - technical planning and analysis agent"
  []
  {:id :architect
   :name "architect"
   :description (slurp (io/resource "clojure_mcp/tools/architect/description.md"))
   :system-message (slurp (io/resource "clojure_mcp/tools/architect/system_message.md"))
   :context false ; No default context
   :enable-tools [:LS :read_file :grep :glob_files :clojure_inspect_project]
   :memory-size 100})

(defn code-critique-config
  "Configuration for the code critique agent - provides code improvement feedback"
  []
  {:id :code-critique
   :name "code_critique"
   :description (slurp (io/resource "clojure_mcp/tools/code_critique/description.md"))
   :system-message (slurp (io/resource "clojure_mcp/tools/code_critique/system_message.md"))
   :context false ; No context needed
   :enable-tools nil ; No tools needed
   :memory-size 35})

(defn clojure-edit-agent-config
  "Configuration for the clojure edit agent - loaded from EDN resource"
  []
  (-> (io/resource "clojure-mcp/agents/clojure_edit_agent.edn")
      slurp
      edn/read-string))

(defn parent-agent-config
  "Configuration for the parent agent - has all tools and uses Clojure REPL system prompt"
  []
  {:id :parent-agent
   :name "parent_agent"
   :description "Parent agent with all tools and Clojure REPL system prompt"
   :system-message (str (slurp (io/resource "clojure-mcp/prompts/system/clojure_repl_form_edit.md"))
                        (slurp (io/resource "clojure-mcp/prompts/system/clojure_form_edit.md")))
   ;; :context true ; Uses default code index and project summary
   :enable-tools [:all] ; Give access to all available tools
   :memory-size false})

(defn make-default-agents
  "Returns a vector of default agent configurations.
   These agents are always available unless explicitly overridden by user configuration."
  []
  [(dispatch-agent-config)
   (architect-config)
   (code-critique-config)
   (clojure-edit-agent-config)])

(defn default-agent-ids
  "Returns a set of default agent IDs for easy checking"
  []
  #{:dispatch-agent :architect :code-critique :clojure-edit-agent})

(defn merge-tool-config-into-agent
  "Merges tool-specific configuration from :tools-config into an agent configuration.
   Only merges keys that make sense for agent configuration.
   
   Args:
   - agent-config: The base agent configuration
   - tool-config: The tool-specific configuration from :tools-config
   
   Returns: Merged agent configuration"
  [agent-config tool-config]
  (if-not tool-config
    agent-config
    ;; Merge specific keys from tool-config
    (merge agent-config
           (select-keys tool-config
                        [:name :context :model :system-message
                         :enable-tools :disable-tools :description
                         :memory-size]))))
