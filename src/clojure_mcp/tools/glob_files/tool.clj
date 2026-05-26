(ns clojure-mcp.tools.glob-files.tool
  "Implementation of the glob-files tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.glob-files.core :as core]
   [clojure-mcp.utils.valid-paths :as valid-paths]
   [clojure-mcp.config :as config] ; Added config require
   [clojure.string :as string]))

;; Factory function to create the tool configuration
(defn create-glob-files-tool
  "Creates the glob-files tool configuration.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  {:tool-type :glob-files
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods for the glob-files tool
(defmethod tool-system/tool-name :glob-files [_]
  "glob_files")

(defmethod tool-system/tool-annotations :glob-files [_]
  {:title "Find Files by Pattern"
   :read-only? true
   :idempotent? true})

(defmethod tool-system/tool-description :glob-files [_]
  "Fast file pattern matching tool that works with any codebase size.
 - Supports glob patterns like \"**/*.clj\" or \"src/**/*.cljs\".
 - Returns matching file paths sorted by modification time (most recent first).
 - Use this tool when you need to find files by name patterns.
 - When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the `dispatch_agent` tool instead")

(defmethod tool-system/tool-schema :glob-files [_]
  {:type :object
   :properties {:path {:type :string
                       :description "Root directory to start the search from (defaults to current working directory)"}
                :pattern {:type :string
                          :description "Glob pattern (e.g. \"**/*.clj\", \"src/**/*.tsx\")"}
                :max_results {:type :integer
                              :description "Maximum number of results to return (default: 1000)"}}
   :required [:pattern]})

(defmethod tool-system/validate-inputs :glob-files [{:keys [nrepl-client-atom]} inputs]
  (let [{:keys [path pattern max_results]} inputs
        nrepl-client-map @nrepl-client-atom ; Dereference atom
        effective-path (or path (config/get-nrepl-user-dir nrepl-client-map))]

    (when-not effective-path
      (throw (ex-info "No path provided and no nrepl-user-dir available" {:inputs inputs})))

    (when-not pattern
      (throw (ex-info "Missing required parameter: pattern" {:inputs inputs})))

    ;; Pass the dereferenced map to validate-path-with-client
    (let [validated-path (valid-paths/validate-path-with-client effective-path nrepl-client-map)]
      (cond-> {:path validated-path
               :pattern pattern}
        max_results (assoc :max-results max_results)))))

(defmethod tool-system/execute-tool :glob-files [_ inputs]
  (let [{:keys [path pattern max-results]} inputs]
    (core/glob-files path pattern :max-results (or max-results 1000))))

(defmethod tool-system/format-results :glob-files [_ result]
  (if (:error result)
    ;; If there's an error, return it with error flag true
    {:result [(:error result)]
     :error true}
    ;; Format the results as a plain text list of filenames
    (let [{:keys [filenames truncated numFiles]} result
          output (cond
                   (empty? filenames) "No files found"

                   :else (str (string/join "\n" filenames)
                              (when truncated
                                (str "\n(Showing " (count filenames) " of " numFiles
                                     " total files. Use max_results parameter to see more.)"))))]
      {:result [output]
       :error false})))

;; Backward compatibility function that returns the registration map
(defn glob-files-tool
  "Returns the registration map for the glob-files tool.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-glob-files-tool nrepl-client-atom)))
