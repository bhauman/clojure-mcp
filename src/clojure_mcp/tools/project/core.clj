(ns clojure-mcp.tools.project.core
  "Core functionality for project inspection and analysis.
   This namespace provides the implementation details for analyzing project structure."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure-mcp.nrepl :as mcp-nrepl]
   [clojure-mcp.config :as config]
   [clojure-mcp.tools.glob-files.core :as glob])
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
  '(try
     {:working-dir (System/getProperty "user.dir")
      :java-version (System/getProperty "java.version")
      :clojure-version (try (clojure-version)
                            (catch Exception _ nil))}
     (catch Exception e
       {:error (str "Failed to gather runtime info: " (.getMessage e))
        :working-dir "."
        :java-version "Unknown"
        :clojure-version "Unknown"})))

(defn read-deps-edn
  "Safely reads and parses deps.edn from the working directory.
   Returns the parsed EDN data or nil if file doesn't exist or parsing fails."
  [working-dir]
  (let [deps-file (File. working-dir "deps.edn")]
    (when (.exists deps-file)
      (try
        (-> deps-file slurp edn/read-string)
        (catch Exception e
          (println "Warning: Failed to read/parse deps.edn:" (.getMessage e))
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
          (println "Warning: Failed to read/parse project.clj:" (.getMessage e))
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
      (println "Warning: Failed to parse project.clj config:" (.getMessage e))
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
      (println "Warning: Failed to extract project.clj details:" (.getMessage e))
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
      (println "Warning: Failed to extract source paths:" (.getMessage e))
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
      (println "Warning: Failed to extract test paths:" (.getMessage e))
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
   - allowed-directories: Optional list of allowed directories

   Returns a formatted string with project details"
  [runtime-data & [allowed-directories]]
  (when runtime-data
    (when-let [{:keys [working-dir java-version clojure-version error]}
               (try
                 (edn/read-string runtime-data)
                 (catch Throwable e
                   (println "Error parsing runtime data:" (ex-message e))
                   nil))]
      (when error
        (println "Runtime error:" error))

      ;; Read and parse project files locally
      (let [deps (read-deps-edn working-dir)
            project-clj (read-project-clj working-dir)
            lein-config (when project-clj (parse-lein-config project-clj))
            project-type (determine-project-type deps project-clj)
            source-paths (extract-source-paths deps lein-config)
            test-paths (extract-test-paths deps lein-config)
            all-paths (concat source-paths test-paths)
            ;; Collect source files locally using glob-files
            source-files (->> (mapcat (fn [path]
                                        (let [clj-files (glob/glob-files working-dir (str path "/**/*.clj") :max-results 1000)
                                              cljs-files (glob/glob-files working-dir (str path "/**/*.cljs") :max-results 1000)
                                              cljc-files (glob/glob-files working-dir (str path "/**/*.cljc") :max-results 1000)
                                              bb-files (glob/glob-files working-dir (str path "/**/*.bb") :max-results 1000)
                                              edn-files (glob/glob-files working-dir (str path "/**/*.edn") :max-results 1000)]
                                          (concat (or (:filenames clj-files) [])
                                                  (or (:filenames cljs-files) [])
                                                  (or (:filenames cljc-files) [])
                                                  (or (:filenames bb-files) [])
                                                  (or (:filenames edn-files) []))))
                                      all-paths)
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
          (println "• Clojure Version:" clojure-version)
          (println "• Java Version:" java-version)

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
                                          (filter #(or (.endsWith % ".clj")
                                                       (.endsWith % ".cljs")
                                                       (.endsWith % ".cljc")))
                                          (map (fn [file-path]
                                                 ;; Remove source path prefix from file path
                                                 (let [relative-path (reduce (fn [path src-path]
                                                                               (if (.startsWith path (str src-path "/"))
                                                                                 (.substring path (inc (count src-path)))
                                                                                 path))
                                                                             file-path
                                                                             all-paths)]
                                                   (-> relative-path
                                                       (.replace "/" ".")
                                                       (.replace "_" "-")
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
              (println "• ... and" (- (count source-files) limit) "more"))))))))

(defn inspect-project
  "Core function to inspect a Clojure project and return formatted information.
   Now uses minimal nREPL calls and does all file processing locally.

   Arguments:
   - nrepl-client: The nREPL client connection

   Returns a map with :outputs (containing the formatted project info) and :error (boolean)"
  [nrepl-client]
  (let [runtime-code (str (inspect-project-code))
        result-promise (promise)
        allowed-directories (config/get-allowed-directories nrepl-client)]
    (try
      (let [runtime-result (mcp-nrepl/tool-eval-code nrepl-client runtime-code)]
        (if (or (nil? runtime-result) (.startsWith runtime-result "Error"))
          (deliver result-promise
                   {:outputs [(or runtime-result "Error during runtime info gathering")]
                    :error true})
          (let [formatted-info (format-project-info runtime-result allowed-directories)]
            (deliver result-promise
                     {:outputs [formatted-info]
                      :error false}))))
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
