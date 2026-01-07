(ns clojure-mcp.tools.deps-read.tool
  "MCP tool implementation for reading files from dependency jars."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.deps-read.core :as core]))

;; Factory function to create the tool configuration
(defn create-deps-read-tool
  "Creates the deps-read tool configuration."
  [nrepl-client-atom]
  {:tool-type :deps-read
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods
(defmethod tool-system/tool-name :deps-read [_]
  "deps_read")

(defmethod tool-system/tool-description :deps-read [_]
  "Read a file from inside a dependency jar.
- Takes file_path in 'jar-path:entry-path' format (from deps_grep results)
- Supports offset and limit for reading portions of large files
- Returns file content with line numbers (like read_file tool)")

(defmethod tool-system/tool-schema :deps-read [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Path in format 'jar-path:entry-path' (e.g., from deps_grep results)"}
                :offset {:type :integer
                         :description "Line offset to start reading from (0-indexed, default 0)"}
                :limit {:type :integer
                        :description "Maximum number of lines to read (default: reads entire file)"}}
   :required [:file_path]})

(defmethod tool-system/validate-inputs :deps-read [_ inputs]
  (let [{:keys [file_path offset limit]} inputs]
    (when-not file_path
      (throw (ex-info "Missing required parameter: file_path" {:inputs inputs})))
    (if-let [{:keys [jar-path entry-path]} (core/parse-jar-entry-path file_path)]
      {:jar-path jar-path
       :entry-path entry-path
       :opts (cond-> {}
               offset (assoc :offset offset)
               limit (assoc :limit limit))}
      (throw (ex-info "Invalid file_path format. Expected 'jar-path:entry-path'"
                      {:file_path file_path})))))

(defmethod tool-system/execute-tool :deps-read [_ {:keys [jar-path entry-path opts]}]
  (core/deps-read jar-path entry-path opts))

(defmethod tool-system/format-results :deps-read [_ result]
  (if (:error result)
    {:result [(str "Error: " (:error result)
                   (when (:details result)
                     (str "\nDetails: " (:details result))))]
     :error true}
    (let [{:keys [content jar-path entry-path line-count total-line-count truncated? offset]} result
          header (str "File: " jar-path ":" entry-path "\n"
                      "Lines: " (inc (or offset 0)) "-" (+ (or offset 0) line-count)
                      " of " total-line-count
                      (when truncated? " (truncated)")
                      "\n\n")]
      {:result [(str header content)]
       :error false})))

;; Backward compatibility function
(defn deps-read-tool
  "Returns the registration map for the deps-read tool."
  [nrepl-client-atom]
  (tool-system/registration-map (create-deps-read-tool nrepl-client-atom)))
