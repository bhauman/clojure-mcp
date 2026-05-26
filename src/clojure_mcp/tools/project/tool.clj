(ns clojure-mcp.tools.project.tool
  "Implementation of project inspection tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.project.core :as core]))

;; Factory function to create the tool configuration
(defn create-project-inspection-tool
  "Creates the project inspection tool configuration"
  [nrepl-client-atom]
  {:tool-type :clojure-inspect-project
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods for the project inspection tool
(defmethod tool-system/tool-name :clojure-inspect-project [_]
  "clojure_inspect_project")

(defmethod tool-system/tool-annotations :clojure-inspect-project [_]
  {:title "Inspect Clojure Project"
   :read-only? true
   :idempotent? true})

(defmethod tool-system/tool-description :clojure-inspect-project [_]
  "Analyzes and provides detailed information about a Clojure project's structure, 
including dependencies, source files, namespaces, and environment details.

This tool helps you understand project organization without having to manually 
explore multiple configuration files. It works with both deps.edn and Leiningen projects.

The tool provides information about:
- Project environment (working directory, Clojure version, Java version)
- Source and test paths
- Dependencies and their versions
- Aliases and their configurations
- Available namespaces
- Source file structure

Use this tool to quickly get oriented in an unfamiliar Clojure codebase or to 
get a high-level overview of your current project.

# Example:
clojure_inspect_project()")

(defmethod tool-system/tool-schema :clojure-inspect-project [_]
  {:type :object
   ;; this is for the anthropic api sdk which currently fails when there are no args ... sigh
   :properties {:explanation {:type :string
                              :description "Short explanation why you chose this tool"}}
   :required [:explanation]})

(defmethod tool-system/validate-inputs :clojure-inspect-project [_ inputs]
  ;; No inputs required for this tool
  inputs)

(defmethod tool-system/execute-tool :clojure-inspect-project [{:keys [nrepl-client-atom]} _]
  ;; Pass the atom directly to core implementation instead of dereferencing
  (core/inspect-project nrepl-client-atom))

(defmethod tool-system/format-results :clojure-inspect-project [_ {:keys [outputs error]}]
  ;; Format the results according to MCP expectations
  {:result outputs
   :error error})

;; Backward compatibility function that returns the registration map
(defn inspect-project-tool [nrepl-client-atom]
  (tool-system/registration-map (create-project-inspection-tool nrepl-client-atom)))
