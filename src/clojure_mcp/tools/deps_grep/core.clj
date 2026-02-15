(ns clojure-mcp.tools.deps-grep.core
  "Core implementation for searching dependency jars.
   Uses clojure CLI for classpath resolution and ripgrep for searching."
  (:require
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [clojure.java.io :as io]
   [clojure-mcp.tools.deps-common.jar-utils :as jar-utils]
   [clojure-mcp.tools.deps-sources.core :as deps-sources]
   [clojure-mcp.utils.shell :as shell-utils]
   [taoensso.timbre :as log]))

;; Cache for base classpath jars, keyed by project directory
(def ^:private classpath-cache (atom {}))

;; Cache for library-filtered jars with sources, keyed by [project-dir library java-sources?]
(def ^:private library-jars-cache (atom {}))

(defn rg-available?
  "Check if ripgrep (rg) is available on the system."
  []
  (shell-utils/binary-available? "rg"))

(defn check-required-binaries!
  "Check that required binaries are available. Returns nil if all present,
   or an error map with :error and :missing-binaries keys."
  []
  (let [required {"clojure" ["-Sdescribe"]}
        missing (->> required
                     (keep (fn [[bin args]]
                             (when-not (apply shell-utils/binary-available? bin args)
                               bin))))]
    (when (seq missing)
      {:error (str "Required binaries not found: " (str/join ", " missing)
                   ". Please install them to use deps_grep.")
       :missing-binaries (vec missing)})))

(defn get-classpath-jars
  "Run `clojure -Spath` in the given directory and return a vector of jar paths.
   Returns nil if classpath resolution fails."
  [project-dir]
  (log/debug "Resolving classpath for:" project-dir)
  (try
    (let [result (shell/with-sh-dir project-dir
                   (shell/sh "clojure" "-Spath"))]
      (if (zero? (:exit result))
        (let [classpath (:out result)
              ;; Use platform-specific path separator (: on Unix, ; on Windows)
              path-sep (re-pattern java.io.File/pathSeparator)
              jars (->> (str/split classpath path-sep)
                        (filter #(str/ends-with? % ".jar"))
                        (filter #(.exists (io/file %)))
                        vec)]
          (log/debug "Found" (count jars) "jars on classpath")
          jars)
        (do
          (log/warn "clojure -Spath failed:" (:err result))
          nil)))
    (catch Exception e
      (log/error e "Failed to resolve classpath")
      nil)))

(defn find-sources-jar
  "Given a jar path, find the corresponding -sources.jar if it exists."
  [jar-path]
  (when (str/ends-with? jar-path ".jar")
    (let [sources-path (str/replace jar-path #"\.jar$" "-sources.jar")
          sources-file (io/file sources-path)]
      (when (.exists sources-file)
        sources-path))))

(defn needs-java-sources?
  "Check if the search options indicate we're looking for Java files."
  [{:keys [type glob]}]
  (or (= "java" type)
      (and glob (re-find #"\.java" glob))))

(defn get-jars-with-sources
  "Given a list of jars and search opts, return jars plus any available sources jars.
   When searching for Java files, downloads missing sources from Maven Central."
  [jars opts]
  (let [;; First find sources jars already in Maven cache
        existing-sources (->> jars
                              (keep find-sources-jar)
                              (remove (set jars)))]
    (if (needs-java-sources? opts)
      ;; For Java searches, also download missing sources
      (let [jars-with-sources (set (map #(str/replace % #"-sources\.jar$" ".jar")
                                        existing-sources))
            jars-missing-sources (remove jars-with-sources jars)
            _ (log/debug "Checking for Java sources for" (count jars-missing-sources) "jars")
            downloaded-sources (deps-sources/ensure-sources-jars! jars-missing-sources)]
        (log/debug "Downloaded" (count downloaded-sources) "sources jars")
        (into (vec jars) (concat existing-sources downloaded-sources)))
      ;; For non-Java searches, just use existing sources
      (into (vec jars) existing-sources))))

(defn parse-library-filter
  "Parse a library filter string into group and optional artifact.
   Returns {:group \"group.id\"} or {:group \"group.id\" :artifact \"name\"}."
  [library]
  (let [parts (str/split library #"/" 2)]
    (if (= 2 (count parts))
      {:group (first parts) :artifact (second parts)}
      {:group (first parts)})))

(defn filter-jars-by-library
  "Filter jars to only those matching the given library filter.
   Library can be a group ID (matches all artifacts) or group/artifact (exact match).
   Uses deps-sources/parse-maven-coords to extract coordinates from jar paths."
  [jars library]
  (let [{:keys [group artifact]} (parse-library-filter library)]
    (filterv (fn [jar-path]
               (when-let [coords (deps-sources/parse-maven-coords jar-path)]
                 (and (= group (:group coords))
                      (or (nil? artifact)
                          (= artifact (:artifact coords))))))
             jars)))

(defn cached-base-jars
  "Get base classpath jars with caching. Returns cached result if available."
  [project-dir]
  (or (get @classpath-cache project-dir)
      (when-let [jars (get-classpath-jars project-dir)]
        (swap! classpath-cache assoc project-dir jars)
        jars)))

(defn clear-classpath-cache!
  "Clear all caches. Useful after deps changes."
  []
  (reset! classpath-cache {})
  (reset! library-jars-cache {}))

(defn list-jar-entries
  "List all entries in a jar file.
   Returns a vector of entry paths or nil on error."
  [jar-path]
  (jar-utils/list-jar-entries jar-path))

(defn glob-matches?
  "Check if a path matches a glob pattern.
   Supports simple patterns like *.clj, *.{clj,cljs}"
  [pattern path]
  (if-not pattern
    true
    (let [;; Convert glob to regex, escaping all regex metacharacters first
          ;; then restoring glob wildcards
          pattern-regex (-> pattern
                            (str/replace #"[.+^$|()\\]" "\\\\$0")
                            (str/replace "*" ".*")
                            (str/replace #"\{([^}]+)\}"
                                         (fn [[_ alts]]
                                           (str "(" (str/replace alts "," "|") ")"))))]
      (boolean (re-find (re-pattern (str pattern-regex "$")) path)))))

(defn type-to-glob
  "Convert a file type (like 'clj') to a glob pattern."
  [type-str]
  (when type-str
    (str "*." type-str)))

(defn filter-entries
  "Filter jar entries by glob and/or type patterns."
  [entries {:keys [glob type]}]
  (let [effective-glob (or glob (type-to-glob type))]
    (if effective-glob
      (filter #(glob-matches? effective-glob %) entries)
      entries)))

(defn search-jar-entry-rg
  "Search using ripgrep. Reads jar entry via Java, pipes content to rg via stdin.
   Supports context lines and multiline patterns."
  [jar-path entry-path pattern {:keys [case-insensitive context-before context-after
                                       context multiline]}]
  (try
    (when-let [content (jar-utils/read-jar-entry jar-path entry-path)]
      (let [rg-args (cond-> ["rg" "-n"]
                      case-insensitive (conj "-i")
                      multiline (conj "-U")
                      context-before (conj "-B" (str context-before))
                      context-after (conj "-A" (str context-after))
                      context (conj "-C" (str context)))
            rg-args (conj rg-args pattern)
            result (apply shell/sh (concat rg-args [:in content]))]
        (when (zero? (:exit result))
          (let [matches (->> (str/split-lines (:out result))
                             (keep (fn [line]
                                     (when-let [[_ line-num sep content]
                                                (re-matches #"(\d+)([:|-])(.*)$" line)]
                                       {:line-num (parse-long line-num)
                                        :content content
                                        :match? (= sep ":")}))))]
            (when (seq matches)
              {:jar jar-path
               :entry entry-path
               :matches (vec matches)})))))
    (catch Exception e
      (log/debug "Error searching" entry-path "in" jar-path ":" (.getMessage e))
      nil)))

(defn search-jar-entry-fallback
  "Fallback search using Java jar reading and Clojure regex.
   Does not support context lines or multiline."
  [jar-path entry-path pattern {:keys [case-insensitive]}]
  (try
    (when-let [content (jar-utils/read-jar-entry jar-path entry-path)]
      (let [lines (str/split-lines content)
            pattern-re (re-pattern (if case-insensitive
                                     (str "(?i)" pattern)
                                     pattern))
            matches (keep-indexed
                     (fn [idx line]
                       (when (re-find pattern-re line)
                         {:line-num (inc idx)
                          :content line
                          :match? true}))
                     lines)]
        (when (seq matches)
          {:jar jar-path
           :entry entry-path
           :matches (vec matches)})))
    (catch Exception e
      (log/debug "Error searching" entry-path "in" jar-path ":" (.getMessage e))
      nil)))

(defn search-jar-entry
  "Search a single entry within a jar. Uses ripgrep if available, otherwise
   falls back to Clojure regex (without context/multiline support).

   Returns a map with :jar, :entry, and :matches. Each match has :line-num,
   :content, and :match? (true for matches, false for context lines)."
  [jar-path entry-path pattern opts]
  (if (rg-available?)
    (search-jar-entry-rg jar-path entry-path pattern opts)
    (search-jar-entry-fallback jar-path entry-path pattern opts)))

(defn deps-grep
  "Search for a pattern in dependency jars.

   Arguments:
   - project-dir: Directory containing deps.edn
   - pattern: Regex pattern to search for
   - opts: Map of options
     :library - Required. Maven group or group/artifact to search
     :glob - Filter files by glob pattern (e.g., \"*.clj\")
     :type - Filter files by type (e.g., \"clj\", \"java\")
     :output-mode - :content, :files-with-matches, or :count
     :case-insensitive - Case insensitive search
     :line-numbers - Include line numbers (default true for content mode)
     :context-before - Lines before match
     :context-after - Lines after match
     :context - Lines before and after
     :head-limit - Limit number of results
     :multiline - Enable multiline matching

   Returns a map with :results and optionally :truncated.

   Requires: clojure CLI. Optional: ripgrep (rg) for context/multiline."
  [project-dir pattern opts]
  (if-let [binary-error (check-required-binaries!)]
    binary-error
    (let [base-jars (cached-base-jars project-dir)]
      (if-not base-jars
        {:error "Failed to resolve classpath. Is this a deps.edn project?"}
        (let [library (:library opts)
              cache-key [project-dir library (needs-java-sources? opts)]
              filtered-jars (filter-jars-by-library base-jars library)]
          (if (empty? filtered-jars)
            {:error (str "No libraries found matching: " (:library opts)
                         ". Use deps_list to see available libraries.")}
            (let [;; Get jars with sources (cached per library)
                  jars (or (get @library-jars-cache cache-key)
                           (let [result (get-jars-with-sources filtered-jars opts)]
                             (swap! library-jars-cache assoc cache-key result)
                             result))
                  {:keys [output-mode head-limit]
                   :or {output-mode :content}} opts
                  all-results (atom [])
                  result-count (atom 0)
                  limit-reached (atom false)]
              ;; Search each jar
              (doseq [jar jars
                      :while (not @limit-reached)]
                (when-let [entries (list-jar-entries jar)]
                  (let [filtered-entries (filter-entries entries opts)]
                    (doseq [entry filtered-entries
                            :while (not @limit-reached)]
                      (when-let [match (search-jar-entry jar entry pattern opts)]
                        (case output-mode
                          :files-with-matches
                          (do
                            (swap! all-results conj {:jar (:jar match)
                                                     :entry (:entry match)})
                            (swap! result-count inc))

                          :count
                          (swap! result-count + (count (:matches match)))

                          ;; :content (default)
                          (do
                            (swap! all-results conj match)
                            (swap! result-count + (count (:matches match)))))

                        (when (and head-limit (>= @result-count head-limit))
                          (reset! limit-reached true)))))))

              (cond-> {:results @all-results}
                (= output-mode :count) (assoc :count @result-count)
                @limit-reached (assoc :truncated true)))))))))

