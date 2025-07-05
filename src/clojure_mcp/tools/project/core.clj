(ns clojure-mcp.tools.project.core
  "Core functionality for project inspection and analysis.
   This namespace provides the implementation details for analyzing project structure."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure-mcp.nrepl :as mcp-nrepl]
   [clojure-mcp.config :as config]
   [clojure-mcp.tools.glob-files.core :as glob]
   [clojure-mcp.utils.valid-paths :as vpaths]
   [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.nio.file Paths]))

(defn to-relative-path
  "Converts an absolute file path to a relative path from the working directory.
   Uses Java NIO Path utilities for proper path handling.
   
   Arguments:
   - working-dir: The base directory (working directory)
   - file-path: The absolute file path to make relative
   
   Returns the relative path as a string, or the original path if relativization fails."
  [working-dir file-path]
  (try
    (let [base-path (Paths/get working-dir (into-array String []))
          target-path (Paths/get file-path (into-array String []))
          relative-path (.relativize base-path target-path)]
      (.toString relative-path))
    (catch Exception _
      ;; Fallback to original path if relativization fails
      file-path)))

(defn inspect-project-code
  "Minimal REPL expression to gather only essential runtime information.
   All file reading and parsing is now done locally."
  []
  ;; this expression is focussed on Clojure java runtime...
  ;; this expression is expected to fail on environments like
  ;; Babashka, Scittle and Basilisp
  '(try
     {:java-version (System/getProperty "java.version")
      :clojure-version (try (clojure-version)
                            (catch Exception _ nil))}
     (catch Exception e
       {:error (str "Failed to gather runtime info: " (.getMessage e))})))

(defn read-deps-edn
  "Safely reads and parses deps.edn from the working directory.
   Returns the parsed EDN data or nil if file doesn't exist or parsing fails."
  [working-dir]
  (let [deps-file (File. working-dir "deps.edn")]
    (when (.exists deps-file)
      (try
        (-> deps-file slurp edn/read-string)
        (catch Exception e
          (log/debug "Failed to read/parse deps.edn:" (.getMessage e))
          nil)))))

(defn read-project-clj
  "Safely reads and parses project.clj from the working directory.
   Returns the parsed Clojure data or nil if file doesn't exist or parsing fails."
  [working-dir]
  (let [project-file (File. working-dir "project.clj")]
    (when (.exists project-file)
      (try
        (-> project-file slurp read-string)
        (catch Exception e
          (log/debug "Failed to read/parse project.clj:" (.getMessage e))
          nil)))))

(defn parse-lein-config
  "Extracts configuration map from a parsed project.clj structure.
   Returns a map of configuration keys/values or empty map if parsing fails."
  [project-clj]
  (try
    (when (and (sequential? project-clj) (>= (count project-clj) 3))
      (let [remaining (drop 3 project-clj)]
        (when (even? (count remaining))
          (->> remaining
               (partition 2)
               (map (fn [[k v]] [k v]))
               (into {})))))
    (catch Exception e
      (log/debug "Failed to parse project.clj config:" (.getMessage e))
      {})))

(defn extract-lein-project-info
  "Extracts basic project information from parsed project.clj.
   Returns a map with :name, :version, :dependencies, :profiles."
  [project-clj lein-config]
  (try
    (when (and (sequential? project-clj) (>= (count project-clj) 3))
      {:name (nth project-clj 1 "Unknown")
       :version (nth project-clj 2 "Unknown")
       :dependencies (get lein-config :dependencies [])
       :profiles (get lein-config :profiles {})})
    (catch Exception e
      (log/debug "Failed to extract project.clj details:" (.getMessage e))
      {:name "Unknown" :version "Unknown" :dependencies [] :profiles {}})))

(defn extract-source-paths
  "Extracts source paths from deps.edn or leiningen config.
   Returns a vector of valid string paths, defaulting to [\"src\"]."
  [deps lein-config]
  (try
    (cond
      deps (let [paths (:paths deps)]
             (if (and paths (sequential? paths) (every? string? paths))
               paths
               ["src"]))
      lein-config (let [paths (:source-paths lein-config)]
                    (if (and paths (sequential? paths) (every? string? paths))
                      paths
                      ["src"]))
      :else ["src"])
    (catch Exception e
      (log/debug "Failed to extract source paths:" (.getMessage e))
      ["src"])))

(defn extract-test-paths
  "Extracts test paths from deps.edn or leiningen config.
   Returns a vector of valid string paths, defaulting to [\"test\"]."
  [deps lein-config]
  (try
    (cond
      deps (let [paths (get-in deps [:aliases :test :extra-paths])]
             (if (and paths (sequential? paths) (every? string? paths))
               paths
               ["test"]))
      lein-config (let [paths (:test-paths lein-config)]
                    (if (and paths (sequential? paths) (every? string? paths))
                      paths
                      ["test"]))
      :else ["test"])
    (catch Exception e
      (log/debug "Failed to extract test paths:" (.getMessage e))
      ["test"])))

(defn determine-project-type
  "Determines the project type based on presence of deps.edn and project.clj."
  [deps project-clj]
  (cond
    (and deps project-clj) "deps.edn + Leiningen"
    deps "deps.edn"
    project-clj "Leiningen"
    :else "Unknown"))

(defn format-project-info
  "Formats the project information into a readable string.
   Now reads project files locally and uses helper functions for parsing.

   Arguments:
   - runtime-data: The minimal runtime data from nREPL as an EDN string
   - allowed-directories: list of allowed directories
   - working-directory: the working directory of the project
   Returns a formatted string with project details"
  [runtime-data allowed-directories working-dir]
  {:pre [working-dir (not-empty allowed-directories)]}
  ;; the Formatting code below should work even if we are unable to get data from
  ;; the nREPL connection
  (let [{:keys [clojure-version java-version]} runtime-data]

    ;; Read and parse project files locally
    (let [deps (read-deps-edn working-dir)
          project-clj (read-project-clj working-dir)
          lein-config (when project-clj (parse-lein-config project-clj))
          project-type (determine-project-type deps project-clj)
          source-paths (extract-source-paths deps lein-config)
          test-paths (extract-test-paths deps lein-config)
          ;; validate all paths are in allowed directories and the working directory
          all-paths (->> (concat source-paths test-paths)
                         (keep #(try (vpaths/validate-path % working-dir allowed-directories)
                                     (catch Exception _ nil))))
          ;; Collect source files locally using a single glob pattern
          source-files (->> all-paths
                            (mapcat (fn [path]
                                      ;; Use a single glob pattern for all extensions
                                      (let [result (glob/glob-files path "**/*.{clj,cljs,cljc,bb,edn}" :max-results 1000)]
                                        (or (:filenames result) []))))
                            ;; Convert absolute paths to relative paths
                            (map #(to-relative-path working-dir %))
                            ;; Sort alphabetically for better browsability
                            sort)]
      (with-out-str
        (println "\nClojure Project Information:")
        (println "==============================")

        (println "\nEnvironment:")
        (println "• Working Directory:" working-dir)
        (println "• Project Type:" project-type)

        (when clojure-version
          (println "• Clojure Version:" clojure-version))

        (when java-version
          (println "• Java Version:" java-version))

        (println "\nSource Paths:")
        (doseq [path source-paths]
          (println "•" path))

        (println "\nTest Paths:")
        (doseq [path test-paths]
          (println "•" path))

        (when allowed-directories
          (println "\nOther Relevant Accessible Directories:")
          (doseq [dir allowed-directories]
            (println "•" dir)))

        (when deps
          (println "\nDependencies:")
          (doseq [[dep coord] (sort-by key (:deps deps))]
            (println "•" dep "=>" coord)))

        (when-let [aliases (:aliases deps)]
          (println "\nAliases:")
          (doseq [[alias config] (sort-by key aliases)]
            (println "•" alias ":" (pr-str config))))

        (when project-clj
          (let [project-info (extract-lein-project-info project-clj lein-config)]
            (println "\nLeiningen Project:")
            (println "• Name:" (:name project-info))
            (println "• Version:" (:version project-info))
            (when-let [deps (:dependencies project-info)]
              (println "\nLeiningen Dependencies:")
              (doseq [[dep version] deps]
                (println "•" dep "=>" version)))
            (when-let [profiles (:profiles project-info)]
              (println "\nLeiningen Profiles:")
              (doseq [[profile config] (sort-by key profiles)]
                (println "•" profile ":" (pr-str config))))))

        (let [limit 50
              ;; Process raw file paths into proper namespace names
              processed-namespaces (->> source-files
                                        (filter #(or (str/ends-with? % ".clj")
                                                     (str/ends-with? % ".cljs")
                                                     (str/ends-with? % ".cljc")))
                                        (map (fn [file-path]
                                               ;; Remove source path prefix from file path
                                               (let [relative-path (reduce (fn [path src-path]
                                                                             (if (str/starts-with? path (str src-path "/"))
                                                                               (.substring path (inc (count src-path)))
                                                                               path))
                                                                           file-path
                                                                           all-paths)]
                                                 (-> relative-path
                                                     (str/replace "/" ".")
                                                     (str/replace "_" "-")
                                                     (str/replace #"\.(clj|cljs|cljc)$" "")))))
                                        ;; Sort namespaces alphabetically
                                        sort
                                        (into []))]
          (println "\nNamespaces (" (count processed-namespaces) "):")
          (doseq [ns-name (take limit processed-namespaces)]
            (println "•" ns-name))
          (when (> (count processed-namespaces) limit)
            (println "• ... and" (- (count processed-namespaces) limit) "more"))

          (println "\nProject Structure (" (count source-files) " files):")
          (doseq [source-file (take limit source-files)]
            (println "•" source-file))
          (when (> (count source-files) limit)
            (println "• ... and" (- (count source-files) limit) "more")))))))

(defn parse-nrepl-result [result]
  (try
    (let [res (edn/read-string result)]
      (when (map? res)
        (if (:error res)
          (do
            (log/debug (str "Project Inspect failed to get data from nREPL server: " (:error res)))
            nil)
          res)))
    (catch Throwable e
      (log/debug "Error parsing runtime data:" (ex-message e))
      nil)))

(defn inspect-project
  "Core function to inspect a Clojure project and return formatted information.
   Now uses minimal nREPL calls and does all file processing locally.

   Arguments:
   - nrepl-client: The nREPL client connection

   Returns a map with :outputs (containing the formatted project info) and :error (boolean)"
  [nrepl-client]
  (let [runtime-code (str (inspect-project-code))
        result-promise (promise)
        allowed-directories (config/get-allowed-directories nrepl-client)
        working-directory (config/get-nrepl-user-dir nrepl-client)]
    (try
      (let [formatted-info (-> (mcp-nrepl/tool-eval-code nrepl-client runtime-code)
                               parse-nrepl-result
                               (format-project-info allowed-directories working-directory))]
        (deliver result-promise
                 {:outputs [formatted-info]
                  :error false}))
      (catch Exception e
        (deliver result-promise
                 {:outputs [(str "Exception during project inspection: " (.getMessage e))]
                  :error true})))
    @result-promise))

(comment
  ;; Test the project inspection in the REPL
  (require '[clojure-mcp.nrepl :as nrepl])
  (def client (nrepl/create {:port 7888}))
  (nrepl/start-polling client)

  ;; Test inspection
  (def result (inspect-project client))
  (println (first (:outputs result)))

  ;; Clean up
  (nrepl/stop-polling client))
