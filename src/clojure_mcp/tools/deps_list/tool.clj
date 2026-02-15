(ns clojure-mcp.tools.deps-list.tool
  "MCP tool implementation for listing project dependencies."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.deps-list.core :as core]
   [clojure-mcp.config :as config]))

;; Factory function to create the tool configuration
(defn create-deps-list-tool
  "Creates the deps-list tool configuration."
  [nrepl-client-atom]
  {:tool-type :deps-list
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods
(defmethod tool-system/tool-name :deps-list [_]
  "deps_list")

(defmethod tool-system/tool-description :deps-list [_]
  "List all dependencies on the project classpath with Maven coordinates.
- Shows group/artifact and version for each dependency
- Use this to discover library names for use with deps_grep's library parameter
- Resolves classpath using `clojure -Spath`")

(defmethod tool-system/tool-schema :deps-list [_]
  {:type :object
   :properties {:pattern {:type :string
                          :description "Optional regex pattern to filter dependencies (matches against group and artifact names)"}}
   :required []})

(defmethod tool-system/validate-inputs :deps-list [{:keys [nrepl-client-atom]} inputs]
  (let [nrepl-client @nrepl-client-atom
        project-dir (config/get-nrepl-user-dir nrepl-client)]
    (when-not project-dir
      (throw (ex-info "No project directory configured" {})))
    {:project-dir project-dir
     :opts (cond-> {}
             (:pattern inputs) (assoc :pattern (:pattern inputs)))}))

(defmethod tool-system/execute-tool :deps-list [_ {:keys [project-dir opts]}]
  (core/deps-list project-dir opts))

(defmethod tool-system/format-results :deps-list [_ result]
  (if (:error result)
    {:result [(:error result)]
     :error true}
    (let [{:keys [dependencies]} result]
      {:result [(if (seq dependencies)
                  (str (count dependencies) " dependencies found:\n"
                       (clojure.string/join
                        "\n"
                        (map (fn [{:keys [group artifact version]}]
                               (str group "/" artifact " " version))
                             dependencies)))
                  "No dependencies found on classpath.")]
       :error false})))

;; Backward compatibility function
(defn deps-list-tool
  "Returns the registration map for the deps-list tool."
  [nrepl-client-atom]
  (tool-system/registration-map (create-deps-list-tool nrepl-client-atom)))
