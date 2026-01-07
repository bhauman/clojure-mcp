(ns clojure-mcp.tools.deps-grep.tool
  "MCP tool implementation for searching dependency jars."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.deps-grep.core :as core]
   [clojure-mcp.config :as config]
   [clojure.string :as string]))

;; Factory function to create the tool configuration
(defn create-deps-grep-tool
  "Creates the deps-grep tool configuration."
  [nrepl-client-atom]
  {:tool-type :deps-grep
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods
(defmethod tool-system/tool-name :deps-grep [_]
  "deps_grep")

(defmethod tool-system/tool-description :deps-grep [_]
  "Search for patterns in dependency jar files on the classpath.
- Uses `clojure -Spath` to resolve the exact dependency jars
- Searches inside jar files for matching content
- Supports glob patterns to filter file types (e.g., \"*.clj\", \"*.java\")
- Three output modes: content (with line numbers), files_with_matches, count
- Use this to find code patterns in your project's dependencies
- Results include both jar path and entry path for use with deps_read tool")

(defmethod tool-system/tool-schema :deps-grep [_]
  {:type :object
   :properties {:pattern {:type :string
                          :description "The regular expression pattern to search for"}
                :glob {:type :string
                       :description "Glob pattern to filter files (e.g., \"*.clj\", \"*.{clj,java}\")"}
                :type {:type :string
                       :description "File type to search (e.g., \"clj\", \"java\"). Alternative to glob."}
                :output_mode {:type :string
                              :enum ["content" "files_with_matches" "count"]
                              :description "Output mode: 'content' shows matching lines, 'files_with_matches' shows file paths, 'count' shows match count"}
                :case_insensitive {:type :boolean
                                   :description "Case insensitive search"}
                :head_limit {:type :integer
                             :description "Limit output to first N results"}}
   :required [:pattern]})

(defmethod tool-system/validate-inputs :deps-grep [{:keys [nrepl-client-atom]} inputs]
  (let [{:keys [pattern glob type output_mode case_insensitive head_limit]} inputs
        nrepl-client @nrepl-client-atom
        project-dir (config/get-nrepl-user-dir nrepl-client)]
    (when-not project-dir
      (throw (ex-info "No project directory configured" {:inputs inputs})))
    (when-not pattern
      (throw (ex-info "Missing required parameter: pattern" {:inputs inputs})))

    {:project-dir project-dir
     :pattern pattern
     :opts (cond-> {}
             glob (assoc :glob glob)
             type (assoc :type type)
             output_mode (assoc :output-mode (keyword (string/replace output_mode "_" "-")))
             case_insensitive (assoc :case-insensitive case_insensitive)
             head_limit (assoc :head-limit head_limit))}))

(defmethod tool-system/execute-tool :deps-grep [_ {:keys [project-dir pattern opts]}]
  (core/deps-grep project-dir pattern opts))

(defmethod tool-system/format-results :deps-grep [_ result]
  (if (:error result)
    {:result [(:error result)]
     :error true}
    (let [{:keys [results count truncated]} result]
      {:result [(cond
                  ;; Count mode
                  (contains? result :count)
                  (str "Found " count " matches"
                       (when truncated " (truncated)"))

                  ;; Files with matches mode
                  (and (seq results) (not (contains? (first results) :matches)))
                  (str "Found " (clojure.core/count results) " files with matches"
                       (when truncated " (truncated)") "\n"
                       (string/join "\n"
                                    (map (fn [{:keys [jar entry]}]
                                           (str jar ":" entry))
                                         results)))

                  ;; Content mode (default)
                  (seq results)
                  (string/join "\n\n"
                               (map (fn [{:keys [jar entry matches]}]
                                      (str "=== " jar ":" entry " ===\n"
                                           (string/join "\n"
                                                        (map (fn [{:keys [line-num content]}]
                                                               (str line-num ": " content))
                                                             matches))))
                                    results))

                  :else
                  "No matches found")]
       :error false})))

;; Backward compatibility function
(defn deps-grep-tool
  "Returns the registration map for the deps-grep tool."
  [nrepl-client-atom]
  (tool-system/registration-map (create-deps-grep-tool nrepl-client-atom)))
