(ns clojure-mcp.tools.glob-files.core
  "Core implementation for the glob-files tool.
   This namespace contains the pure functionality without any MCP-specific code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [taoensso.timbre :as log])
  (:import
   (java.nio.file FileVisitResult FileSystems Files Paths SimpleFileVisitor)))

 ;; Cache tool availability to avoid repeated shell calls
(def ^:private tool-availability (atom {}))

(defn- check-tool-available?
  "Check if a command-line tool is available and cache the result.
   Tests actual tool execution rather than just PATH existence for better reliability."
  [dir tool-name]
  (if-let [cached (@tool-availability tool-name)]
    cached
    (let [result (try
                   (case tool-name
                     "rg"
                     (zero? (:exit (shell/with-sh-dir dir
                                     (shell/sh tool-name "--version"))))

                     "find"
                   ;; Test with a simple, cross-platform find command
                     (zero? (:exit (shell/with-sh-dir dir
                                     (shell/sh tool-name "." "-name" "." "-type" "d"))))

                   ;; Default fallback test
                     (zero? (:exit (shell/with-sh-dir dir
                                     (shell/sh tool-name "--help")))))
                   (catch Exception _ false))]
      (swap! tool-availability assoc tool-name result)
      result)))

(defn glob-with-rg
  "Uses ripgrep (rg) to find files matching glob patterns.
   Ripgrep is typically faster than find for large codebases.
   
   Arguments:
   - dir: Directory to search in
   - pattern: Glob pattern (e.g. \"**/*.clj\", \"src/**/*.tsx\")
   - max-results: Maximum number of results to return
   
   Returns a map with search results or error information."
  [dir pattern max-results]
  (let [start-time (System/currentTimeMillis)
        ;; Convert glob patterns to rg format and handle depth
        cmd-args (if (re-matches #"^\*\.[^/]+$" pattern)
                   ;; For simple *.ext patterns, limit to max-depth 1 (root level only)
                   ["rg" "--files" "--max-depth" "1" "--glob" pattern dir]
                   ;; For other patterns, search recursively 
                   ["rg" "--files" "--glob" pattern dir])]
    (log/debug "Using rg:" (str/join " " cmd-args))
    (let [result (shell/with-sh-dir dir
                   (apply shell/sh cmd-args))
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
        ;; For invalid patterns, return empty results like NIO does
        (let [end-time (System/currentTimeMillis)]
          {:filenames []
           :numFiles 0
           :durationMs (- end-time start-time)
           :truncated false})))))

(defn glob-with-find
  "Uses the Unix find command to locate files matching glob patterns.
   
   Arguments:
   - dir: Directory to search in
   - pattern: Glob pattern (e.g. \"**/*.clj\", \"src/**/*.tsx\")
   - max-results: Maximum number of results to return
   
   Returns a map with search results or error information."
  [dir pattern max-results]
  (let [start-time (System/currentTimeMillis)
        ;; Convert glob pattern to find-compatible format
        find-pattern (cond
                       ;; Handle **/*.ext patterns
                       (re-matches #"^\*\*/\*\.[^/]+$" pattern)
                       (str "*." (last (str/split pattern #"\.")))

                       ;; Handle src/**/*.ext patterns  
                       (re-matches #"^[^/]+/\*\*/\*\.[^/]+$" pattern)
                       (let [parts (str/split pattern #"/")]
                         (str "*." (last (str/split (last parts) #"\."))))

                       ;; Simple *.ext patterns
                       (re-matches #"^\*\.[^/]+$" pattern)
                       pattern

                       ;; Default: try as-is
                       :else pattern)
        cmd-args ["find" dir "-name" find-pattern "-type" "f"]]
    (log/debug "Using find:" (str/join " " cmd-args))
    (let [result (shell/with-sh-dir dir
                   (apply shell/sh cmd-args))
          exit-code (:exit result)]
      (if (zero? exit-code)
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
        ;; For invalid patterns, return empty results like NIO does  
        (let [end-time (System/currentTimeMillis)]
          {:filenames []
           :numFiles 0
           :durationMs (- end-time start-time)
           :truncated false})))))

(defn glob-with-nio
  "Fast file pattern matching using Java NIO and glob patterns like **.clj or src/**/*.cljs.

   Arguments:
   - path: Root directory to start the search from
   - pattern: Glob pattern (e.g. \"**/*.clj\", \"src/**/*.tsx\")
   - max-results: Maximum number of results to return (default: 1000)

   Returns a map with:
   - :filenames - Sequence of matching file paths, sorted by modification time (newest first)
   - :numFiles - Number of matching files found
   - :durationMs - Duration of the search in milliseconds
   - :truncated - Boolean indicating if results were truncated due to max-results limit"
  [dir pattern & {:keys [max-results] :or {max-results 1000}}]
  (log/debug "Using Java NIO: pattern=" pattern "dir=" dir)
  (let [start (System/currentTimeMillis)
        dir-file (io/file dir)]
    (if (and (.exists dir-file) (.isDirectory dir-file))
      (try
        ;; Special case for patterns starting with "**/" to also include root files
        (let [should-check-root? (re-matches #"^\*\*/.*$" pattern)
              root-pattern (when should-check-root?
                             (subs pattern 3)) ; Remove the "**/" prefix

              path (Paths/get (.getAbsolutePath dir-file) (into-array String []))
              matcher (try
                        (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))
                        (catch Exception e
                          ;; Handle invalid glob pattern
                          (let [end (System/currentTimeMillis)]
                            (throw (ex-info
                                    (str "Invalid glob pattern: " (.getMessage e))
                                    {:durationMs (- end start)})))))
              root-matcher (when should-check-root?
                             (try
                               (.getPathMatcher (FileSystems/getDefault) (str "glob:" root-pattern))
                               (catch Exception _ nil)))
              matches (atom [])
              truncated (atom false)]

          ;; Add special handling for root files if using the "**/*.ext" pattern
          (when (and should-check-root? root-matcher)
            (doseq [file (.listFiles dir-file)]
              (when (and (.isFile file)
                         (< (count @matches) max-results)
                         (.matches root-matcher
                                   (Paths/get (.getName file) (into-array String []))))
                (swap! matches conj {:path (str file)
                                     :mtime (.lastModified file)}))))

          (Files/walkFileTree
           path
           (proxy [SimpleFileVisitor] []
             (preVisitDirectory [dir _attrs]
               (let [dirName (str (.getFileName dir))]
                 (if (.startsWith dirName ".")
                   FileVisitResult/SKIP_SUBTREE
                   FileVisitResult/CONTINUE)))
             (visitFile [file _attrs]
               (let [rel (.relativize path file)]
                 (when (and (< (count @matches) max-results)
                            (.matches matcher rel)
                            ;; Skip root files we've already added with the special case
                            (not (and should-check-root?
                                      (= (.getNameCount rel) 1)
                                      root-matcher
                                      (.matches root-matcher (.getFileName rel)))))
                   (swap! matches conj {:path (str file)
                                        :mtime (.lastModified (.toFile file))}))
                 (when (>= (count @matches) max-results)
                   (reset! truncated true))
                 FileVisitResult/CONTINUE))))

          (let [end (System/currentTimeMillis)
                duration (- end start)
                sorted-results (->> @matches
                                    (sort-by :mtime >)
                                    (map :path)
                                    vec)]
            {:filenames sorted-results
             :numFiles (count sorted-results)
             :durationMs duration
             :truncated @truncated}))

        (catch Exception e
          (if (ex-data e)
            (let [data (ex-data e)]
              ;; For invalid glob pattern, return empty results rather than an error
              {:filenames []
               :numFiles 0
               :durationMs (:durationMs data)})
            ;; For other exceptions, return an error
            {:error (.getMessage e)
             :durationMs (- (System/currentTimeMillis) start)})))
      {:error (str dir " is not a valid directory")
       :durationMs 0})))

(defn glob-files
  "Fast file pattern matching using glob patterns like **.clj or src/**/*.cljs.
   
   Automatically selects the best available tool:
   - ripgrep (rg) for maximum performance when available
   - Unix find command as fallback 
   - Java NIO as final fallback for cross-platform compatibility

   Arguments:
   - dir: Root directory to start the search from
   - pattern: Glob pattern (e.g. \"**/*.clj\", \"src/**/*.tsx\")
   - max-results: Maximum number of results to return (default: 1000)

   Returns a map with:
   - :filenames - Sequence of matching file paths, sorted by modification time (newest first)
   - :numFiles - Number of matching files found
   - :durationMs - Duration of the search in milliseconds
   - :truncated - Boolean indicating if results were truncated due to max-results limit"
  [dir pattern & {:keys [max-results] :or {max-results 1000}}]
  (let [dir-file (io/file dir)]
    (if (and (.exists dir-file) (.isDirectory dir-file))
      (try
        ;; Check tool availability and prefer rg > find > NIO
                ;; Check tool availability and prefer rg > find > NIO
        (cond
          (check-tool-available? dir "rg")
          (do (log/debug "Selected tool: rg")
              (glob-with-rg dir pattern max-results))

          (check-tool-available? dir "find")
          (do (log/debug "Selected tool: find")
              (glob-with-find dir pattern max-results))

          :else
          (do (log/debug "Selected tool: Java NIO")
              (glob-with-nio dir pattern :max-results max-results)))
        (catch Exception e
          {:error (.getMessage e)
           :durationMs 0}))
      {:error (str dir " is not a valid directory")
       :durationMs 0})))

