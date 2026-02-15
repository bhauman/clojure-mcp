(ns clojure-mcp.tools.deps-read.core
  "Core implementation for reading files from dependency jars."
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure-mcp.tools.deps-common.jar-utils :as jar-utils]
   [taoensso.timbre :as log]))

(defn list-jar-entries
  "List all entries in a jar file.
   Returns a vector of entry paths or nil on error."
  [jar-path]
  (jar-utils/list-jar-entries jar-path))

(defn read-jar-entry
  "Read a file from inside a jar.

   Arguments:
   - jar-path: Path to the jar file
   - entry-path: Path within the jar (e.g., 'clojure/core.clj')
   - opts: Map of options
     :offset - Line offset (0-indexed, default 0)
     :limit - Maximum number of lines to read
     :max-line-length - Maximum length per line before truncation (default 2000)

   Returns a map with:
   - :content - The file contents as a string with line numbers
   - :jar-path - The jar path
   - :entry-path - The entry path
   - :line-count - Number of lines returned
   - :total-line-count - Total lines in file (when truncated)
   - :truncated? - Whether output was truncated
   - :offset - The offset used

   Or on error:
   - :error - Error message"
  [jar-path entry-path & {:keys [offset limit max-line-length]
                          :or {offset 0 max-line-length 2000}}]
  (try
    ;; Validate inputs
    (when (neg? offset)
      (throw (ex-info "Offset must be >= 0" {:offset offset})))
    (when (and limit (<= limit 0))
      (throw (ex-info "Limit must be > 0" {:limit limit})))
    ;; Verify jar exists
    (when-not (.exists (io/file jar-path))
      (throw (ex-info "Jar file not found" {:jar-path jar-path})))

    ;; Read entry from jar using Java zip support
    (if-let [content (jar-utils/read-jar-entry jar-path entry-path)]
      (let [all-lines (str/split-lines content)
            total-line-count (count all-lines)
            ;; Apply offset and limit
            offset-lines (drop offset all-lines)
            limited-lines (if limit
                            (take limit offset-lines)
                            offset-lines)
            ;; Truncate long lines
            processed-lines (map (fn [line]
                                   (if (> (count line) max-line-length)
                                     (str (subs line 0 max-line-length) "...")
                                     line))
                                 limited-lines)
            line-count (count processed-lines)
            truncated? (and limit (> (count offset-lines) limit))
            ;; Format with line numbers (1-indexed, accounting for offset)
            formatted-content (str/join "\n"
                                        (map-indexed
                                         (fn [idx line]
                                           (format "%6d→%s" (+ offset idx 1) line))
                                         processed-lines))]
        {:content formatted-content
         :jar-path jar-path
         :entry-path entry-path
         :line-count line-count
         :total-line-count total-line-count
         :truncated? truncated?
         :offset offset})
      {:error (str "Entry not found: " entry-path " in " jar-path)})
    (catch Exception e
      {:error (.getMessage e)})))

(defn parse-jar-entry-path
  "Parse a combined jar:entry path into components.
   Handles paths like '/path/to/lib.jar:clojure/core.clj'

   Returns {:jar-path \"...\" :entry-path \"...\"} or nil if invalid."
  [combined-path]
  (when combined-path
    ;; Find the .jar: separator
    (when-let [jar-end (str/index-of combined-path ".jar:")]
      (let [jar-path (subs combined-path 0 (+ jar-end 4))
            entry-path (subs combined-path (+ jar-end 5))]
        (when (and (seq jar-path) (seq entry-path))
          {:jar-path jar-path
           :entry-path entry-path})))))

(defn deps-read
  "Read a file from a dependency jar.

   Arguments:
   - jar-path: Path to the jar file
   - entry-path: Path within the jar
   - opts: Map of options (see read-jar-entry)

   Alternatively, provide a combined path:
   - path: Combined 'jar-path:entry-path' format

   Returns the file content with line numbers."
  ([path opts]
   (if-let [{:keys [jar-path entry-path]} (parse-jar-entry-path path)]
     (deps-read jar-path entry-path opts)
     {:error (str "Invalid path format. Expected 'jar-path:entry-path', got: " path)}))
  ([jar-path entry-path opts]
   (read-jar-entry jar-path entry-path
                   :offset (or (:offset opts) 0)
                   :limit (:limit opts)
                   :max-line-length (or (:max-line-length opts) 2000))))
