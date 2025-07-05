(ns clojure-mcp.tools.grep.core
  "Core implementation for the grep tool.
   This namespace contains the pure functionality without any MCP-specific code."
  (:require
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]))

 ;; Cache tool availability to avoid repeated shell calls
(def ^:private tool-availability (atom {}))

(defn- check-tool-available?
  "Check if a command-line tool is available and cache the result.
   Tests actual tool execution rather than just PATH existence for better reliability."
  [tool-name]
  (if-let [cached (@tool-availability tool-name)]
    cached
    (let [result (try
                   (let [test-flag (case tool-name
                                     "rg" "--version"
                                     "grep" "--version"
                                     "--help")
                         result (shell/sh tool-name test-flag)]
                     (zero? (:exit result)))
                   (catch Exception _ false))]
      (swap! tool-availability assoc tool-name result)
      result)))

(defn grep-with-rg
  "Uses ripgrep (rg) command to search file contents.
   Ripgrep is typically faster than grep for large codebases.
   
   Arguments:
   - path: Directory to search in
   - pattern: Regular expression pattern to search for
   - include: Optional file pattern to include
   - max-results: Maximum number of results to return
   
   Returns a map with search results or error information."
  [path pattern include max-results]
  (log/debug "Using ripgrep (rg) for search - pattern:" pattern "path:" path "include:" include)
  (let [start-time (System/currentTimeMillis)
        ;; Build rg command args
        cmd-args (as-> ["rg" "--files-with-matches" "--no-heading"] args
                   (if include
                     (concat args ["--glob" include])
                     args)
                   (concat args ["-e" pattern path]))
        result (apply shell/sh cmd-args)
        exit-code (:exit result)]
    (if (or (zero? exit-code) (= exit-code 1)) ;; 1 means no matches, which is not an error
      (let [end-time (System/currentTimeMillis)
            duration (- end-time start-time)
            matching-files (when (seq (:out result))
                             (str/split-lines (:out result)))
            file-count (count matching-files)
            file-times (when matching-files
                         (map (fn [file-path]
                                {:path file-path
                                 :mtime (.lastModified (io/file file-path))})
                              matching-files))
            ;; Sort by modification time (newest first) and limit results
            sorted-limited-files (as-> file-times $
                                   (sort-by :mtime > $)
                                   (take max-results $)
                                   (mapv :path $))]
        {:filenames sorted-limited-files
         :numFiles file-count
         :durationMs duration
         :truncated (> file-count max-results)})
      {:error (str "rg error: " (:err result))
       :durationMs (- (System/currentTimeMillis) start-time)})))

(defn grep-with-command
  "Uses the system grep command to search file contents.
   Uses extended regular expression syntax (-E flag) for advanced patterns.
   Supports multiple file types with brace expansion (e.g., \"*.{clj,md}\").
   
   Arguments:
   - path: Directory to search in
   - pattern: Regular expression pattern to search for
   - include: Optional file pattern to include
   - max-results: Maximum number of results to return
   
   Returns a map with search results or error information."
  [path pattern include max-results]
  (log/debug "Using system grep for search - pattern:" pattern "path:" path "include:" include)
  (let [start-time (System/currentTimeMillis)
        ;; Parse the include pattern to handle multiple extensions
        include-patterns (if (and include (.contains include "{"))
                           (let [pattern-match (re-find #"\*\.\{(.+)\}" include)]
                             (when pattern-match
                               (map #(str "*." %) (clojure.string/split (second pattern-match) #","))))
                           [include])
        ;; Build command with multiple --include flags if needed
        cmd-args (as-> ["grep" "-E" "-l" "-r" "--exclude-dir=.*"] args
                   (if (and include (> (count include-patterns) 1))
                     (concat args (mapv #(str "--include=" %) include-patterns))
                     (if include
                       (concat args [(str "--include=" include)])
                       args))
                   (concat args [pattern path]))
        result (apply shell/sh cmd-args)
        exit-code (:exit result)]
    (if (or (zero? exit-code) (= exit-code 1)) ;; 1 means no matches, which is not an error
      (let [end-time (System/currentTimeMillis)
            duration (- end-time start-time)
            matching-files (when (seq (:out result))
                             (str/split-lines (:out result)))
            file-count (count matching-files)
            file-times (when matching-files
                         (map (fn [file-path]
                                {:path file-path
                                 :mtime (.lastModified (io/file file-path))})
                              matching-files))
            ;; Sort by modification time (newest first) and limit results
            sorted-limited-files (as-> file-times $
                                   (sort-by :mtime > $)
                                   (take max-results $)
                                   (mapv :path $))]
        {:filenames sorted-limited-files
         :numFiles file-count
         :durationMs duration
         :truncated (> file-count max-results)})
      {:error (str "grep error: " (:err result))
       :durationMs (- (System/currentTimeMillis) start-time)})))

(defn grep-with-java
  "Fallback implementation that uses Java to search file contents.
   This implementation recursively traverses the directory structure,
   reading each file line by line and checking for matches using regular expressions.
   
   Features:
   - Pure Java implementation that works without external commands
   - Supports file filtering with glob-like patterns
   - Stops searching a file as soon as a match is found to improve performance
   - Sorts results by file modification time
   - Handles errors gracefully by skipping problematic files
   
   Arguments:
   - path: Directory to search in
   - pattern: Regular expression pattern to search for
   - include: Optional file pattern to include (e.g. \"*.clj\", \"*.{clj,cljs}\")
   - max-results: Maximum number of results to return
   
   Returns a map with:
   - :filenames - Vector of matching file paths sorted by modification time
   - :numFiles - Number of files containing matches
   - :durationMs - Time taken for the search in milliseconds
   - :truncated - Boolean indicating if results were truncated"
  [path pattern include max-results]
  (log/debug "Using Java implementation for search - pattern:" pattern "path:" path "include:" include)
  (let [start-time (System/currentTimeMillis)
        dir-file (io/file path)
        all-matches (atom [])
        include-pattern (when include
                          (re-pattern (str ".*\\.("
                                           (-> include
                                               (str/replace #"^\*\." "")
                                               (str/replace #"\*\.\{(.+)\}" "$1")
                                               (str/replace #"," "|"))
                                           ")$")))
        pattern-regex (re-pattern pattern)]

    ;; Recursive function to search files
    (letfn [(search-directory [dir]
              (doseq [file (.listFiles dir)]
                (cond
                  (and (.isDirectory file)
                       (not (str/starts-with? (.getName file) ".")))
                  (search-directory file)

                  (.isFile file)
                  (let [file-path (.getAbsolutePath file)
                        file-name (.getName file)]
                    (when (or (nil? include-pattern)
                              (re-matches include-pattern file-name))
                      (try
                        (with-open [rdr (io/reader file)]
                          (loop [lines (line-seq rdr)]
                            (when (seq lines)
                              (if (re-find pattern-regex (first lines))
                                (swap! all-matches conj {:path file-path
                                                         :mtime (.lastModified file)})
                                (recur (rest lines))))))
                        (catch Exception _ nil)))))))]

      ;; Start the search
      (search-directory dir-file)

      (let [end-time (System/currentTimeMillis)
            duration (- end-time start-time)
            total-count (count @all-matches)
            sorted-results (->> @all-matches
                                (sort-by :mtime >)
                                (take max-results)
                                (map :path)
                                vec)]
        {:filenames sorted-results
         :numFiles total-count
         :durationMs duration
         :truncated (> total-count max-results)}))))

(defn grep-file-with-lines
  "Search within a single file and return matching lines with line numbers.
   
   Arguments:
   - file-path: Path to the file to search
   - pattern: Regular expression pattern to search for
   
   Returns a map with:
   - :lines - Vector of maps with :line-number and :content
   - :numMatches - Number of matching lines found
   - :durationMs - Time taken for the search in milliseconds"
  [file-path pattern]
  (let [start-time (System/currentTimeMillis)
        pattern-regex (re-pattern pattern)
        matches (atom [])]
    (try
      (with-open [rdr (io/reader file-path)]
        (loop [lines (line-seq rdr)
               line-num 1]
          (when (seq lines)
            (let [line (first lines)]
              (when (re-find pattern-regex line)
                (swap! matches conj {:line-number line-num
                                     :content line}))
              (recur (rest lines) (inc line-num))))))
      {:lines @matches
       :numMatches (count @matches)
       :durationMs (- (System/currentTimeMillis) start-time)}
      (catch Exception e
        {:error (.getMessage e)
         :durationMs (- (System/currentTimeMillis) start-time)}))))

(defn grep-files
  "Fast content search tool that searches file contents using regular expressions.
   Can search within a single file (returning matching lines) or a directory (returning matching files).
   
   Arguments:
   - path: File or directory to search in (defaults to current working directory)
   - pattern: Regular expression pattern to search for
   - include: Optional file pattern to include (e.g. \"*.clj\", \"*.{clj,cljs}\") - only for directories
   - max-results: Maximum number of results to return (default: 1000)
   
   Returns a map with:
   For directories:
   - :filenames - Vector of files containing the pattern, sorted by modification time
   - :numFiles - Number of matching files found
   - :durationMs - Time taken for the search in milliseconds
   - :truncated - Boolean indicating if results were truncated
   
   For files:
   - :lines - Vector of maps with :line-number and :content
   - :numMatches - Number of matching lines found
   - :durationMs - Time taken for the search in milliseconds"
  [path pattern & {:keys [include max-results] :or {include nil max-results 1000}}]
  (let [start-time (System/currentTimeMillis)
        file (io/file path)]
    (cond
      ;; If path is a file, search within it for matching lines
      (and (.exists file) (.isFile file))
      (grep-file-with-lines path pattern)

      ;; If path is a directory, search for files containing the pattern
      (and (.exists file) (.isDirectory file))
      (try
        ;; Check tool availability and prefer rg > grep > java
        (cond
          (check-tool-available? "rg")
          (do
            (log/info "Selected tool: ripgrep (rg) for pattern search")
            (grep-with-rg path pattern include max-results))

          (check-tool-available? "grep")
          (do
            (log/info "Selected tool: system grep for pattern search")
            (grep-with-command path pattern include max-results))

          :else
          (do
            (log/info "Selected tool: Java implementation for pattern search")
            (grep-with-java path pattern include max-results)))
        (catch Exception e
          {:error (.getMessage e)
           :durationMs (- (System/currentTimeMillis) start-time)}))

      ;; Path doesn't exist or is neither file nor directory
      :else
      {:error (str path " is not a valid file or directory")
       :durationMs 0})))
