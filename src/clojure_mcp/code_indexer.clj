(ns clojure-mcp.code-indexer
  "A tool for creating condensed indexes of Clojure codebases using collapsed views.
   
   This namespace provides functions to:
   - Find all Clojure files in a directory using glob-files
   - Generate collapsed views of each file showing function signatures
   - Format multiple collapsed views into a single indexed document
   - Track file modification timestamps
   
   The collapsed views use the pattern-core functionality to show only
   function signatures by default, making large codebases navigable."
  (:require
   [clojure-mcp.tools.unified-read-file.pattern-core :as pattern]
   [clojure-mcp.tools.glob-files.core :as glob]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn find-clojure-files
  "Finds all Clojure files (*.clj, *.cljs, *.cljc) in the given directory using glob-files.
   Returns a vector of absolute file paths sorted by modification time (newest first)."
  [source-dir]
  (let [result (glob/glob-files source-dir "**/*.{clj,cljs,cljc}")]
    (if (:error result)
      []
      (vec (:filenames result)))))

(defn index-file
  "Indexes a single Clojure file, returning a map with:
   - :path - the file path
   - :collapsed-view - the collapsed view of the file content
   - :pattern-info - metadata about the collapsed view
   - :last-modified - timestamp as milliseconds since epoch
   - :last-modified-instant - Java Instant of last modification
   - :error - error message if indexing failed"
  [file-path]
  (try
    (let [file (io/file file-path)
          collapsed-view (pattern/generate-collapsed-view file-path nil nil)
          timestamp (.lastModified file)]
      {:path file-path
       :collapsed-view (:view collapsed-view)
       :pattern-info (:pattern-info collapsed-view)
       :last-modified timestamp
       :last-modified-instant (java.time.Instant/ofEpochMilli timestamp)})
    (catch Exception e
      {:path file-path
       :error (.getMessage e)})))

(defn index-directory
  "Indexes all Clojure files in a directory, returning a vector of maps with 
   file path, collapsed view, and timestamp information."
  [source-dir]
  (let [files (find-clojure-files source-dir)]
    (vec
     (for [file-path files]
       (index-file file-path)))))

(defn format-collapsed-views
  "Formats a vector of indexed files into a single string with clear delimiters.
   Each file section includes the file path, last modified timestamp, and collapsed content.
   Files are separated by a line of 80 equals signs."
  [indexed-files]
  (let [separator (str "\n" (apply str (repeat 80 "=")) "\n")
        file-sections
        (for [{:keys [path collapsed-view error last-modified-instant]} indexed-files]
          (if error
            (str "FILE: " path "\n"
                 "ERROR: " error)
            (str "FILE: " path "\n"
                 "LAST MODIFIED: " last-modified-instant "\n"
                 "CONTENT:\n"
                 collapsed-view)))]
    (str/join separator file-sections)))

(defn index-and-format
  "Convenience function that indexes a directory and returns the formatted string."
  [source-dir]
  (-> (index-directory source-dir)
      (format-collapsed-views)))

(defn save-index-to-file
  "Indexes a directory and saves the formatted output to a file.
   Returns a map with :files-indexed, :output-file, and :size-bytes."
  [source-dir output-file]
  (let [indexed-content (index-and-format source-dir)]
    (spit output-file indexed-content)
    {:files-indexed (count (find-clojure-files source-dir))
     :output-file output-file
     :size-bytes (count indexed-content)}))

(defn find-source-files
  "Finds all Clojure source files excluding test files.
   Test files are identified by being in a /test/ directory or having _test suffix."
  [source-dir]
  (->> (find-clojure-files source-dir)
       (remove #(or (str/includes? % "/test/")
                    (str/includes? % "_test.clj")
                    (str/includes? % "_test.cljs")
                    (str/includes? % "_test.cljc")))))

(defn index-source-directory
  "Indexes only source files (non-test) in a directory.
   Returns a vector of maps with file path, collapsed view, and timestamp information."
  [source-dir]
  (let [files (find-source-files source-dir)]
    (vec
     (for [file-path files]
       (index-file file-path)))))

(defn save-source-index-to-file
  "Indexes only source files in a directory and saves the formatted output to a file.
   Excludes test files from the index.
   Returns a map with :files-indexed, :output-file, and :size-bytes."
  [source-dir output-file]
  (let [indexed-files (index-source-directory source-dir)
        indexed-content (format-collapsed-views indexed-files)]
    (spit output-file indexed-content)
    {:files-indexed (count indexed-files)
     :output-file output-file
     :size-bytes (count indexed-content)}))

(defn map-project
  "Creates a comprehensive index of a Clojure project across multiple directories.
   
   Takes a map of options:
   - :dirs - Vector of directories to index (defaults to [current-working-directory])
            Can also accept a single string for backwards compatibility
   - :include-tests - Whether to include test files (defaults to false)
   - :out-file - Output file path (defaults to \"llm_code_index.txt\")
   
   Returns a map with :files-indexed, :output-file, :size-bytes, and :time-ms.
   
   Examples:
   (map-project {})  ; Index current directory
   (map-project {:dirs [\"/path/to/src\" \"/path/to/lib\"]})
   (map-project {:dirs \"/single/path\"})  ; Single string also works
   (map-project {:include-tests true :out-file \"full-index.txt\"})"
  [{:keys [dirs include-tests out-file]
    :or {dirs [(System/getProperty "user.dir")]
         include-tests false
         out-file "llm_code_index.txt"}}]
  (let [;; Ensure dirs is a vector
        dirs-vec (if (string? dirs) [dirs] dirs)
        start-time (System/currentTimeMillis)

        ;; Index each directory and collect results
        all-indexed-files
        (vec (mapcat (fn [dir]
                       (if include-tests
                         (index-directory dir)
                         (index-source-directory dir)))
                     dirs-vec))

        ;; Format with directory separators if multiple dirs
        formatted-content
        (if (> (count dirs-vec) 1)
          (let [dir-sections
                (for [dir dirs-vec]
                  (let [dir-files (if include-tests
                                    (index-directory dir)
                                    (index-source-directory dir))]
                    (str "\n" (apply str (repeat 80 "#")) "\n"
                         "# DIRECTORY: " dir "\n"
                         "# FILES: " (count dir-files) "\n"
                         (apply str (repeat 80 "#")) "\n\n"
                         (format-collapsed-views dir-files))))]
            (str/join "\n\n" dir-sections))
          (format-collapsed-views all-indexed-files))

        _ (spit out-file formatted-content)
        end-time (System/currentTimeMillis)]

    {:files-indexed (count all-indexed-files)
     :directories (count dirs-vec)
     :output-file out-file
     :size-bytes (count formatted-content)
     :time-ms (- end-time start-time)}))