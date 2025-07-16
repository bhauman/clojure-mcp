(ns clojure-mcp.tools.think.tool
  "Implementation of the think tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]))

(defmethod tool-system/tool-name :think [_]
  "think")

(defmethod tool-system/tool-description :think [_]
  "Use the tool to think about something. It will not obtain new information or make any changes to the repository, but just log the thought. Use it when complex reasoning or brainstorming is needed. 
Common use cases:
* when having difficulty finding/reading the files you need
* when having difficulty writing out code
* when having difficulty using the tools or the clojure tools
* When exploring a repository and discovering the source of a bug, call this tool to brainstorm several unique ways of fixing the bug, and assess which change(s) are likely to be simplest and most effective.
* After receiving test results, use this tool to brainstorm ways to fix failing tests.
* When planning a complex refactoring, use this tool to outline different approaches and their tradeoffs.
* When designing a new feature, use this tool to think through architecture decisions and implementation details.
* When debugging a complex issue, use this tool to organize your thoughts and hypotheses.
The tool simply logs your thought process for better transparency and does not execute any code or make changes.")

(defmethod tool-system/tool-schema :think [_]
  {:type "object"
   :properties {"thought" {:type "string"
                           :description "The thought to log"}}
   :required ["thought"]})

(defmethod tool-system/validate-inputs :think [_ {:keys [thought] :as inputs}]
  (when-not thought
    (throw (ex-info "Missing required parameter: thought" {:inputs inputs})))
  ;; Return the validated inputs
  inputs)

(defmethod tool-system/execute-tool :think [_ {:keys [thought]}]
  {:result "Your thought has been logged."
   :error false})

(defmethod tool-system/format-results :think [_ result]
  (if (:error result)
    {:result [(:result result)]
     :error true}
    {:result [(:result result)]
     :error false}))

(defn create-think-tool [nrepl-client-atom]
  {:tool-type :think
   :nrepl-client-atom nrepl-client-atom})

(defn think-tool
  "Returns the registration map for the think tool.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-think-tool nrepl-client-atom)))
