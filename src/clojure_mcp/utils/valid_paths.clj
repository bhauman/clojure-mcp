(ns clojure-mcp.utils.valid-paths
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure-mcp.config :as config]))

(defn validate-path
  "Validates that a path is within allowed directories.
   
   Parameters:
   - path: The path to validate (can be relative or absolute)
   - current-working-directory: The absolute path of the current working directory
   - allowed-directories: A sequence of absolute paths representing allowed directories
   
   Returns:
   - The normalized absolute path if valid
   - Throws an exception if the path is not within any allowed directory"
  [path current-working-directory allowed-directories]
  (let [cwd-file (io/file current-working-directory)
        path-file (io/file path)]

    ;; Validate current working directory is absolute
    (when-not (.isAbsolute cwd-file)
      (throw (ex-info (str "Current working directory must be absolute \nCWD:" current-working-directory)
                      {:cwd current-working-directory})))

    ;; Validate all allowed directories are absolute
    (let [non-absolute (filter #(not (.isAbsolute (io/file %))) allowed-directories)]
      (when (seq non-absolute)
        (throw (ex-info "All allowed directories must be absolute paths"
                        {:non-absolute-dirs non-absolute}))))

    ;; Normalize the allowed directories upfront
    (let [canonical-allowed-dirs (map #(.getCanonicalPath (io/file %))
                                      (filter identity allowed-directories))

          ;; Normalize the path (resolve relative paths against CWD)
          abs-path-file (if (.isAbsolute path-file)
                          path-file
                          (io/file cwd-file path))
          normalized-path (.getCanonicalPath abs-path-file)]

      ;; Check if path is within any allowed directory
      (if (or (empty? canonical-allowed-dirs) ;; If no allowed directories, accept all paths
              (some (fn [allowed-canonical]
                      ;; Check if normalized path starts with allowed dir plus separator
                      ;; or if it equals the allowed dir exactly
                      (or (= normalized-path allowed-canonical)
                          (str/starts-with? normalized-path
                                            (str allowed-canonical
                                                 (System/getProperty "file.separator")))))
                    canonical-allowed-dirs))
        normalized-path
        (throw (ex-info (str "Your path:\n" normalized-path
                             " is outside the allowed directories:\n" (str/join "\n" allowed-directories))
                        {:path normalized-path
                         :allowed-dirs allowed-directories}))))))

(defn path-exists? [path]
  (.exists (io/file path)))

(defn validate-path-with-client
  "Validates a path using settings from the nrepl-client.
   
   Parameters:
   - path: The path to validate (can be relative or absolute)
   - nrepl-client-map: The nREPL client map (dereferenced atom)
   
   Returns:
   - The normalized absolute path if valid
   - Throws an exception if the path is invalid or if required settings are missing"
  [path nrepl-client]
  (let [current-dir (config/get-nrepl-user-dir nrepl-client)
        allowed-dirs (config/get-allowed-directories nrepl-client)]

    (when-not current-dir
      (throw (ex-info "Missing nrepl-user-dir in config"
                      {:client-keys (keys nrepl-client)})))

    (when-not allowed-dirs
      (throw (ex-info "Missing allowed-directories in config"
                      {:client-keys (keys nrepl-client)})))

    (validate-path path current-dir allowed-dirs)))

  (defn- babashka-shebang?
    [file-path]
    (when (path-exists? file-path)
      (try
        (with-open [r (io/reader file-path)]
          (let [line (-> r line-seq first)]
            (and line
                 (or (str/starts-with? line "#!/usr/bin/env bb")
                     (str/starts-with? line "#!/usr/bin/bb")
                     (str/starts-with? line "#!/bin/bb")))))
        (catch Exception _ false))))

  (defn clojure-file?
    "Checks if a file path has a Clojure-related extension or Babashka shebang."

     Supported extensions:
     - .clj (Clojure)
     - .cljs (ClojureScript)
     - .cljc (Clojure/ClojureScript shared)
     - .bb (Babashka)
     - .edn (Extensible Data Notation)
     - .lpy (Librepl)
     Also detects files starting with a Babashka shebang (`bb`)."
    [file-path]
    (when file-path
      (let [lower-path (str/lower-case file-path)]
        (or (str/ends-with? lower-path ".clj")
            (str/ends-with? lower-path ".cljs")
            (str/ends-with? lower-path ".cljc")
            (str/ends-with? lower-path ".bb")
            (str/ends-with? lower-path ".lpy")
            (str/ends-with? lower-path ".edn")
            (babashka-shebang? file-path)))))

(defn extract-paths-from-bash-command
  "Extract file/directory paths from a bash command string.
   
   Returns a set of path strings that the command might access.
   Only extracts paths that look like filesystem paths:
   - Absolute paths: /path/to/file
   - Relative paths: ./file, ../dir/file  
   - Home directory: ~/file
   - Current/parent directory: . or ..
   - Quoted paths with spaces: '/path with spaces'
   
   Avoids false positives like search patterns in quotes, 
   regex patterns, URLs, etc.
   
   Examples:
     (extract-paths-from-bash-command \"ls /usr/bin\")
     => #{\"usr/bin\"}
     
     (extract-paths-from-bash-command \"find . -name '*.clj'\")
     => #{\".\"}
     
     (extract-paths-from-bash-command \"echo 'not/a/path'\")
     => #{}"
  [command]
  (when-not (str/blank? command)
    (let [;; Regex patterns for different path types  
          absolute-pattern #"(?<=\s|^)/[^\s\"'`;<>|&]+(?=\s|$)" ; /path/to/file
          relative-pattern #"(?<=\s|^)\.{1,2}/[^\s\"'`;<>|&]*(?=\s|$)" ; ./file ../dir
          home-pattern #"(?<=\s|^)~/[^\s\"'`;<>|&]*(?=\s|$)" ; ~/file
          current-parent #"(?<=\s|^)\.{1,2}(?=\s|$)" ; . or ..
          quoted-pattern #"[\"']([^\"']+)[\"']" ; "quoted content"

          ;; Extract all matches
          absolute-paths (re-seq absolute-pattern command)
          relative-paths (re-seq relative-pattern command)
          home-paths (re-seq home-pattern command)
          dot-paths (re-seq current-parent command)

          ;; For quoted strings, only include if they look like filesystem paths
          quoted-paths (->> (re-seq quoted-pattern command)
                            (map second) ; Get capture group content
                            (filter #(and (str/includes? % "/")
                                          (or (str/starts-with? % "/")
                                              (str/starts-with? % "./")
                                              (str/starts-with? % "../")
                                              (str/starts-with? % "~/")))))]
      (->> (concat absolute-paths relative-paths home-paths dot-paths quoted-paths)
           (remove str/blank?)
           set))))

(defn preprocess-path
  "Preprocess a path extracted from a bash command for validation.
   
   Handles:
   - Home directory expansion: ~/file -> /home/user/file  
   - Leaves other paths unchanged for validate-path to handle
   
   Examples:
     (preprocess-path \"~/config\") => \"/home/user/config\"
     (preprocess-path \"./file\") => \"./file\""
  [path]
  (cond
    ;; Expand home directory paths
    (str/starts-with? path "~/")
    (str (System/getProperty "user.home") (subs path 1))

    ;; Handle bare ~ (home directory itself)
    (= path "~")
    (System/getProperty "user.home")

    ;; All other paths pass through unchanged
    :else path))

(defn validate-bash-command-paths
  "Extract and validate all filesystem paths from a bash command.
   
   This function combines path extraction, preprocessing, and validation
   to ensure a bash command only accesses allowed directories.
   
   Parameters:
   - command: The bash command string to analyze  
   - current-working-directory: The current working directory (absolute path)
   - allowed-directories: Sequence of allowed directory paths
   
   Returns:
   - Set of normalized absolute paths if all paths are valid
   - Empty set if no paths found in command (command is safe)
   
   Throws:
   - Exception if any path is invalid, with details about failed paths
   
   Examples:
     (validate-bash-command-paths \"ls ./src /tmp\" \"/home/user\" [\"/home/user\" \"/tmp\"])
     => #{absolute-path-to-src absolute-path-to-tmp}"
  [command current-working-directory allowed-directories]
  (if-let [extracted-paths (extract-paths-from-bash-command command)]
    (let [;; Preprocess all paths (expand ~ etc.)
          preprocessed-paths (map preprocess-path extracted-paths)

          ;; Validate each path and collect results
          validation-results (for [path preprocessed-paths]
                               (try
                                 {:path path
                                  :original (first (filter #(= (preprocess-path %) path) extracted-paths))
                                  :status :valid
                                  :normalized (validate-path path current-working-directory allowed-directories)}
                                 (catch Exception e
                                   {:path path
                                    :original (first (filter #(= (preprocess-path %) path) extracted-paths))
                                    :status :invalid
                                    :error (.getMessage e)})))

          ;; Separate valid and invalid results
          valid-results (filter #(= (:status %) :valid) validation-results)
          invalid-results (filter #(= (:status %) :invalid) validation-results)]

      (if (empty? invalid-results)
        ;; All paths valid - return normalized paths
        (set (map :normalized valid-results))
        ;; Some paths invalid - throw with details
        (let [error-details (->> invalid-results
                                 (map #(str "'" (:original %) "' -> " (:error %)))
                                 (str/join "\n"))]
          (throw (ex-info (str "Invalid paths in bash command:\n" error-details)
                          {:command command
                           :invalid-paths invalid-results
                           :valid-paths valid-results})))))
    ;; No paths found - return empty set (command is safe)
    #{}))
