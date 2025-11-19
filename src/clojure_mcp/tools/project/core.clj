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
   [clojure-mcp.utils.file :as file-utils]
   [taoensso.timbre :as log])
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

(defn read-deps-edn
  "Safely reads and parses deps.edn from the working directory.
   Returns the parsed EDN data or nil if file doesn't exist or parsing fails."
  [working-dir]
  (let [deps-file (File. working-dir "deps.edn")]
    (when (.exists deps-file)
      (try
        (-> deps-file file-utils/slurp-utf8 edn/read-string)
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
        (-> project-file file-utils/slurp-utf8 read-string)
        (catch Exception e
          (log/debug "Failed to read/parse project.clj:" (.getMessage e))
          nil)))))

(defn read-bb-edn
  "Safely reads and parses bb.edn from the working directory.
   Returns the parsed EDN data or nil if file doesn't exist or parsing fails."
  [working-dir]
  (let [bb-file (File. working-dir "bb.edn")]
    (try
      (when (.exists bb-file)
        (edn/read-string (file-utils/slurp-utf8 bb-file)))
      (catch Exception e
        (log/warn "Failed to read or parse bb.edn:" (.getMessage e))
        nil))))

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

(defn extract-bb-source-paths
  "Extracts source paths from bb.edn configuration."
  [bb-config]
  (if bb-config
    (or (:paths bb-config) [])
    []))

(defn extract-bb-tasks
  "Extracts tasks from bb.edn configuration."
  [bb-config]
  (when bb-config
    (:tasks bb-config)))

(defn extract-source-paths
  "Extracts source paths from deps.edn, leiningen config, or bb.edn.
   Returns a vector of valid string paths, defaulting to [\"src\"]."
  [deps lein-config bb-config]
  (try
    (cond
      bb-config (let [paths (extract-bb-source-paths bb-config)]
                  (if (and paths (sequential? paths) (every? string? paths))
                    paths
                    ["src"]))
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
  [deps lein-config _bb-config]
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
  "Determines the project type based on presence of deps.edn, project.clj, and bb.edn."
  [deps project-clj bb-config]
  (cond
    (and deps project-clj bb-config) "deps.edn + Leiningen + Babashka"
    (and deps bb-config) "deps.edn + Babashka"
    (and project-clj bb-config) "Leiningen + Babashka"
    bb-config "Babashka"
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
   - nrepl-env-type: the nrepl env type :clj, :bb, :unknown
   Returns a formatted string with project details"
  [runtime-data allowed-directories working-dir _nrepl-env-type]
  {:pre [working-dir (not-empty allowed-directories)]}
  ;; the Formatting code below should work even if we are unable to get data from
  ;; the nREPL connection
  (let [{:keys [clojure java babashka _basilisp _python]} runtime-data
        ;; Read and parse project files locally
        deps (read-deps-edn working-dir)
        bb-config (when babashka (read-bb-edn working-dir))
        project-clj (read-project-clj working-dir)
        lein-config (when project-clj (parse-lein-config project-clj))
        project-type (determine-project-type deps project-clj bb-config)
        source-paths (extract-source-paths deps lein-config bb-config)
        test-paths (extract-test-paths deps lein-config bb-config)
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
                          sort)
        sb (StringBuilder.)]
    (.append sb "\nClojure Project Information:\n")
    (.append sb "==============================\n")

    (.append sb "\nEnvironment:\n")
    (.append sb (str "• Working Directory: " working-dir "\n"))
    (.append sb (str "• Project Type: " project-type "\n"))

    (when clojure
      (.append sb (str "• Clojure Version: " clojure "\n")))

    (when java
      (.append sb (str "• Java Version: " java "\n")))

    (when babashka
      (.append sb (str "• Babashka Version: " babashka "\n"))
      (.append sb "• Note: This is a Babashka project\n"))

    (.append sb "\nSource Paths:\n")
    (doseq [path source-paths]
      (.append sb (str "• " path "\n")))

    (.append sb "\nTest Paths:\n")
    (doseq [path test-paths]
      (.append sb (str "• " path "\n")))

    (when allowed-directories
      (.append sb "\nOther Relevant Accessible Directories:\n")
      (doseq [dir allowed-directories]
        (.append sb (str "• " dir "\n"))))

    (when deps
      (.append sb "\nDependencies:\n")
      (doseq [[dep coord] (sort-by key (:deps deps))]
        (.append sb (str "• " dep " => " coord "\n"))))

    (when-let [aliases (:aliases deps)]
      (.append sb "\nAliases:\n")
      (doseq [[alias config] (sort-by key aliases)]
        (.append sb (str "• " alias " : " (pr-str config) "\n"))))

    (when bb-config
      (when-let [tasks (extract-bb-tasks bb-config)]
        (.append sb "\nBabashka Tasks:\n")
        (doseq [[task-name task-config] (sort-by key (dissoc tasks :requires))]
          (let [doc (:doc task-config)
                task-desc (if doc (str task-name " - " doc) (str task-name))]
            (.append sb (str "• " task-desc "\n"))))))

    (when project-clj
      (let [project-info (extract-lein-project-info project-clj lein-config)]
        (.append sb "\nLeiningen Project:\n")
        (.append sb (str "• Name: " (:name project-info) "\n"))
        (.append sb (str "• Version: " (:version project-info) "\n"))
        (when-let [deps (:dependencies project-info)]
          (.append sb "\nLeiningen Dependencies:\n")
          (doseq [[dep version] deps]
            (.append sb (str "• " dep " => " version "\n"))))
        (when-let [profiles (:profiles project-info)]
          (.append sb "\nLeiningen Profiles:\n")
          (doseq [[profile config] (sort-by key profiles)]
            (.append sb (str "• " profile " : " (pr-str config) "\n"))))))

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
      (.append sb (str "\nNamespaces (" (count processed-namespaces) "):\n"))
      (doseq [ns-name (take limit processed-namespaces)]
        (.append sb (str "• " ns-name "\n")))
      (when (> (count processed-namespaces) limit)
        (.append sb (str "• ... and " (- (count processed-namespaces) limit) " more\n")))

      (.append sb (str "\nProject Structure (" (count source-files) " files):\n"))
      (doseq [source-file (take limit source-files)]
        (.append sb (str "• " source-file "\n")))
      (when (> (count source-files) limit)
        (.append sb (str "• ... and " (- (count source-files) limit) " more\n"))))
    (.toString sb)))

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

(defn format-describe [{:keys [versions]}]
  (if (:babashka versions)
    (select-keys versions [:babashka])
    (->> versions
         (map (fn [[k {:keys [version-string]}]]
                [k version-string]))
         (into {}))))

(defn inspect-project
  "Core function to inspect a Clojure project and return formatted information.
   Now uses minimal nREPL calls and does all file processing locally.
   Caches the result in the nrepl-client-atom for efficiency.

   Arguments:
   - nrepl-client-atom: The atom containing the nREPL client connection

   Returns a map with :outputs (containing the formatted project info) and :error (boolean)"
  [nrepl-client-atom]
  ;; Check if we have cached project info
  (if-let [cached-info (get @nrepl-client-atom ::clojure-project-info)]
    (do
      (log/debug "Returning cached project info")
      cached-info)
    ;; No cache, compute the project info
    (let [nrepl-client @nrepl-client-atom
          allowed-directories (config/get-allowed-directories nrepl-client)
          working-directory (config/get-nrepl-user-dir nrepl-client)
          nrepl-env-type (config/get-nrepl-env-type nrepl-client)]
      (try
        (when-let [formatted-info (some-> (mcp-nrepl/describe nrepl-client)
                                          format-describe
                                          (format-project-info allowed-directories working-directory nrepl-env-type))]
          (let [result {:outputs [formatted-info]
                        :error false}]
            ;; Cache the result in the atom
            (swap! nrepl-client-atom assoc ::clojure-project-info result)
            (log/debug "Cached project info for future use")
            result))
        (catch Exception e
          {:outputs [(str "Exception during project inspection: " (.getMessage e))]
           :error true})))))
