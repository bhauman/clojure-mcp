(ns clojure-mcp.tools.unified-read-file.core
  "Core implementation for the read-file tool.
   This namespace contains the pure functionality without any MCP-specific code."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn read-file
  "Reads a file from the filesystem, with optional line offset and limit.
   
   Parameters:
   - path: The validated and normalized path to the file
   - offset: Line number to start reading from (0-indexed, default 0)
   - limit: Maximum number of lines to read (default 2000)
   - max-line-length: Maximum length per line before truncation (default 1000)
   
   Returns a map with:
   - :content - The file contents as a string
   - :path - The absolute path to the file
   - :truncated? - Whether the file was truncated due to line limit
   - :truncated-by - The reason for truncation (e.g., 'max-lines')
   - :size - The file size in bytes
   - :line-count - The number of lines returned
   - :offset - The line offset used
   - :max-line-length - The max line length used
   - :line-lengths-truncated? - Whether any lines were truncated in length
   
   If the file doesn't exist or cannot be read, returns a map with :error key"
  [path offset limit & {:keys [max-line-length] :or {max-line-length 1000}}]
  (let [file (io/file path)]
    (if (.exists file)
      (if (.isFile file)
        (try
          (if (and (nil? limit) (zero? offset) (nil? max-line-length))
            ;; Simple case - just read the whole file
            {:content (slurp file)
             :path (.getAbsolutePath file)
             :truncated? false}
            ;; Complex case with limits
            (let [size (.length file)
                  lines (with-open [rdr (io/reader file)]
                          (doall
                           (cond->> (drop offset (line-seq rdr))
                             limit (take (inc limit))
                             true (map
                                   (fn [line]
                                     (if (and max-line-length (> (count line) max-line-length))
                                       (str (subs line 0 max-line-length) "...")
                                       line))))))
                  truncated-by-lines? (and limit (> (count lines) limit))
                  content-lines (if truncated-by-lines? (take limit lines) lines)
                  content (str/join "\n" content-lines)]
              {:content content
               :path (.getAbsolutePath file)
               :truncated? truncated-by-lines?
               :truncated-by (when truncated-by-lines? "max-lines")
               :size size
               :line-count (count content-lines)
               :offset offset
               :max-line-length max-line-length
               :line-lengths-truncated? (and max-line-length
                                             (some #(.contains % "...") lines))}))
          (catch Exception e
            {:error (str "Error reading file: " (.getMessage e))}))
        {:error (str path " is not a file")})
      {:error (str path " does not exist")})))