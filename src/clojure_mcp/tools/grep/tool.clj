(ns clojure-mcp.tools.grep.tool
  "Implementation of the grep tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.grep.core :as core]
   [clojure-mcp.utils.valid-paths :as valid-paths]
   [clojure-mcp.config :as config] ; Added config require
   [clojure.string :as string]))

;; Factory function to create the tool configuration
(defn create-grep-tool
  "Creates the grep tool configuration.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  {:tool-type :grep
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods for the grep tool
(defmethod tool-system/tool-name :grep [_]
  "grep")

(defmethod tool-system/tool-description :grep [_]
  "Fast content search tool that works with any codebase size.
- Finds the paths to files that have matching contents using regular expressions.
- Supports full regex syntax (eg. \"log.*Error\", \"function\\s+\\w+\", etc.).
- Filter files by pattern with the include parameter (eg. \"*.js\", \"*.{ts,tsx}\").
- Returns matching file paths sorted by modification time.
- Use this tool when you need to find files containing specific patterns.
- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the `dispatch_agent` tool instead")

(defmethod tool-system/tool-schema :grep [_]
  {:type :object
   :properties {:path {:type :string
                       :description "The directory to search in. Defaults to the current working directory."}
                :pattern {:type :string
                          :description "The regular expression pattern to search for in file contents"}
                :include {:type :string
                          :description "File pattern to include in the search (e.g. \"*.clj\", \"*.{clj,cljs}\")"}
                :max_results {:type :integer
                              :description "Maximum number of results to return (default: 1000)"}}
   :required [:pattern]})

(defmethod tool-system/validate-inputs :grep [{:keys [nrepl-client-atom]} inputs]
  (let [{:keys [path pattern include max_results]} inputs
        nrepl-client-map @nrepl-client-atom ; Dereference atom
        effective-path (or path (config/get-nrepl-user-dir nrepl-client-map))]
    (when-not effective-path
      (throw (ex-info "Missing required parameter: path" {:inputs inputs})))

    (when-not pattern
      (throw (ex-info "Missing required parameter: pattern" {:inputs inputs})))

    ;; Pass the dereferenced map to validate-path-with-client
    (let [validated-path (valid-paths/validate-path-with-client effective-path nrepl-client-map)]
      (cond-> {:path validated-path
               :pattern pattern}
        include (assoc :include include)
        max_results (assoc :max-results max_results)))))

(defmethod tool-system/execute-tool :grep [_ inputs]
  (let [{:keys [path pattern include max-results]} inputs]
    (core/grep-files path pattern
                     :include include
                     :max-results (or max-results 1000))))

(defmethod tool-system/format-results :grep [_ result]
  (if (:error result)
    ;; If there's an error, return it with error flag true
    {:result [(:error result)]
     :error true}
    ;; Otherwise, format the results in a human-readable way
    (cond
      ;; File search results (searching a directory)
      (contains? result :filenames)
      (let [{:keys [filenames numFiles truncated]} result]
        {:result [(cond
                    (nil? filenames)
                    "No files found"

                    (zero? numFiles)
                    "No files found"

                    :else
                    (let [base-message (str "Found " numFiles " file" (when-not (= numFiles 1) "s") "\n"
                                            (string/join "\n" filenames))]
                      (if truncated
                        (str base-message "\n(Results are truncated. Consider using a more specific path or pattern.)")
                        base-message)))]
         :error false})

      ;; Line search results (searching within a file)
      (contains? result :lines)
      (let [{:keys [lines numMatches]} result]
        {:result [(cond
                    (nil? lines)
                    "No matches found"

                    (zero? numMatches)
                    "No matches found"

                    :else
                    (str "Found " numMatches " match" (when-not (= numMatches 1) "es") "\n"
                         (string/join "\n"
                                      (map #(str (:line-number %) ": " (:content %))
                                           lines))))]
         :error false})

      ;; Fallback (shouldn't happen with current implementation)
      :else
      {:result ["Unknown result format"]
       :error false})))

;; Backward compatibility function that returns the registration map
(defn grep-tool
  "Returns the registration map for the grep tool.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-grep-tool nrepl-client-atom)))
