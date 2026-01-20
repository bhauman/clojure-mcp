(ns clojure-mcp.tools.deps-sources.core
  "Core implementation for downloading and caching Java source jars.
   Fetches sources from Maven Central for jars that don't have local sources."
  (:require
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [clojure.java.io :as io]
   [taoensso.timbre :as log]))

;; Global cache directory for downloaded source jars
(def sources-cache-dir
  (io/file (System/getProperty "user.home") ".clojure-mcp" "deps_cache"))

;; File tracking jars that don't have sources available
(def no-sources-file
  (io/file sources-cache-dir ".no-sources"))

(defn parse-maven-coords
  "Parse a jar path to extract Maven coordinates.

   Example input: /Users/foo/.m2/repository/org/clojure/clojure/1.11.1/clojure-1.11.1.jar
   Returns: {:group \"org.clojure\" :artifact \"clojure\" :version \"1.11.1\" :jar-name \"clojure-1.11.1.jar\"}

   Returns nil if the path doesn't match expected Maven repository structure."
  [jar-path]
  (when-let [match (re-find #"\.m2/repository/(.+)/([^/]+)/([^/]+)/([^/]+\.jar)$" jar-path)]
    (let [[_ group-path artifact version jar-name] match
          group-id (str/replace group-path "/" ".")]
      {:group group-id
       :artifact artifact
       :version version
       :jar-name jar-name
       :group-path group-path})))

(defn sources-jar-url
  "Build Maven Central URL for a sources jar from coordinates.

   Example: {:group \"org.clojure\" :artifact \"clojure\" :version \"1.11.1\"}
   Returns: \"https://repo1.maven.org/maven2/org/clojure/clojure/1.11.1/clojure-1.11.1-sources.jar\""
  [{:keys [group-path artifact version]}]
  (format "https://repo1.maven.org/maven2/%s/%s/%s/%s-%s-sources.jar"
          group-path artifact version artifact version))

(defn cached-sources-path
  "Return the local cache path for a sources jar.

   Example: {:group-path \"org/clojure\" :artifact \"clojure\" :version \"1.11.1\"}
   Returns: ~/.clojure-mcp/deps_cache/org/clojure/clojure/1.11.1/clojure-1.11.1-sources.jar"
  [{:keys [group-path artifact version]}]
  (io/file sources-cache-dir group-path artifact version
           (str artifact "-" version "-sources.jar")))

(defn load-no-sources-set
  "Load the set of jar identifiers known to have no sources available."
  []
  (if (.exists no-sources-file)
    (set (str/split-lines (slurp no-sources-file)))
    #{}))

(defn save-no-sources!
  "Add a jar identifier to the no-sources file."
  [jar-id]
  (locking no-sources-file
    (.mkdirs (.getParentFile no-sources-file))
    (spit no-sources-file (str jar-id "\n") :append true)))

(defn jar-identifier
  "Create a unique identifier for a jar from its coordinates."
  [{:keys [group artifact version]}]
  (str group ":" artifact ":" version))

(defn download-sources-jar!
  "Download a sources jar from Maven Central to the cache.

   Returns the path to the downloaded jar, or nil if download failed (404 or other error)."
  [coords]
  (let [url (sources-jar-url coords)
        dest (cached-sources-path coords)
        dest-dir (.getParentFile dest)]
    (log/debug "Downloading sources jar:" url)
    (.mkdirs dest-dir)
    (let [result (shell/sh "curl" "--fail" "--silent" "--location"
                           "--output" (.getAbsolutePath dest)
                           url)]
      (if (zero? (:exit result))
        (do
          (log/debug "Downloaded:" (.getAbsolutePath dest))
          (.getAbsolutePath dest))
        (do
          (log/debug "Sources not available for:" (jar-identifier coords))
          ;; Clean up any partial download
          (when (.exists dest)
            (.delete dest))
          ;; Record in negative cache
          (save-no-sources! (jar-identifier coords))
          nil)))))

(defn find-sources-jar-for
  "Find or download sources jar for a given jar path.

   Checks in order:
   1. Sources jar next to original jar in Maven cache
   2. Our downloaded cache
   3. Downloads from Maven Central (if not in negative cache)

   Returns the sources jar path or nil if not available."
  [jar-path]
  (when-let [coords (parse-maven-coords jar-path)]
    (let [jar-id (jar-identifier coords)
          ;; Check if we already know this has no sources
          no-sources-set (load-no-sources-set)]
      (if (contains? no-sources-set jar-id)
        nil
        ;; Check Maven cache first (next to the jar)
        (let [maven-sources (str/replace jar-path #"\.jar$" "-sources.jar")]
          (cond
            ;; Found in Maven cache
            (.exists (io/file maven-sources))
            maven-sources

            ;; Check our cache
            (let [cached (cached-sources-path coords)]
              (.exists cached))
            (.getAbsolutePath (cached-sources-path coords))

            ;; Download from Maven Central
            :else
            (download-sources-jar! coords)))))))

(defn ensure-sources-jars!
  "Ensure sources jars are available for the given list of jar paths.
   Downloads missing ones from Maven Central in parallel.

   Returns a vector of available sources jar paths (may be fewer than input
   if some jars don't have sources published)."
  [jars]
  (log/debug "Ensuring sources for" (count jars) "jars")
  (->> jars
       (pmap find-sources-jar-for)
       (remove nil?)
       vec))
