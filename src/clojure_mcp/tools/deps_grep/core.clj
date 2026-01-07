(ns clojure-mcp.tools.deps-grep.core
  "Core implementation for searching dependency jars.
   Uses clojure CLI for classpath resolution and ripgrep for searching."
  (:require
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [clojure.java.io :as io]
   [taoensso.timbre :as log]))

;; Cache for classpath jars, keyed by project directory
(def ^:private classpath-cache (atom {}))

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
              jars (->> (str/split classpath #":")
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

(defn get-jars-with-sources
  "Given a list of jars, return jars plus any available sources jars."
  [jars]
  (let [sources-jars (->> jars
                          (keep find-sources-jar)
                          (remove (set jars)))] ; don't duplicate if already included
    (into (vec jars) sources-jars)))

(defn cached-classpath-jars
  "Get classpath jars with caching. Returns cached result if available.
   Includes sources jars when available."
  [project-dir]
  (or (get @classpath-cache project-dir)
      (when-let [jars (get-classpath-jars project-dir)]
        (let [all-jars (get-jars-with-sources jars)]
          (log/debug "Found" (- (count all-jars) (count jars)) "sources jars")
          (swap! classpath-cache assoc project-dir all-jars)
          all-jars))))

(defn clear-classpath-cache!
  "Clear the classpath cache. Useful after deps changes."
  []
  (reset! classpath-cache {}))

(defn list-jar-entries
  "List all entries in a jar file using unzip -Z1.
   Returns a vector of entry paths or nil on error."
  [jar-path]
  (try
    (let [result (shell/sh "unzip" "-Z1" jar-path)]
      (if (zero? (:exit result))
        (str/split-lines (:out result))
        nil))
    (catch Exception e
      (log/debug "Failed to list jar entries:" jar-path (.getMessage e))
      nil)))

(defn glob-matches?
  "Check if a path matches a glob pattern.
   Supports simple patterns like *.clj, *.{clj,cljs}"
  [pattern path]
  (if-not pattern
    true
    (let [;; Convert glob to regex
          ;; Handle {a,b} patterns
          pattern-regex (-> pattern
                            (str/replace "." "\\.")
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

(defn search-jar-entry
  "Search a single entry within a jar using unzip -p and rg.
   Returns a map with :jar, :entry, and :matches."
  [jar-path entry-path pattern {:keys [case-insensitive
                                        context-before context-after context
                                        multiline]}]
  (try
    (let [;; Build rg command args
          rg-args (cond-> ["rg" "-n"]
                    case-insensitive (conj "-i")
                    multiline (conj "-U")
                    context-before (conj "-B" (str context-before))
                    context-after (conj "-A" (str context-after))
                    context (conj "-C" (str context))
                    true (conj pattern))
          ;; Run unzip -p | rg
          unzip-result (shell/sh "unzip" "-p" jar-path entry-path)
          _ (when-not (zero? (:exit unzip-result))
              (throw (ex-info "unzip failed" {:jar jar-path :entry entry-path})))
          rg-result (shell/sh "bash" "-c"
                              (str "echo " (pr-str (:out unzip-result))
                                   " | rg " (str/join " " (map pr-str (rest rg-args)))))]
      (when (zero? (:exit rg-result))
        {:jar jar-path
         :entry entry-path
         :output (:out rg-result)}))
    (catch Exception e
      (log/debug "Error searching" entry-path "in" jar-path ":" (.getMessage e))
      nil)))

(defn search-jar-entry-simple
  "Search a single entry within a jar. Simpler implementation using temp file approach."
  [jar-path entry-path pattern opts]
  (try
    (let [unzip-result (shell/sh "unzip" "-p" jar-path entry-path)]
      (when (zero? (:exit unzip-result))
        (let [content (:out unzip-result)
              lines (str/split-lines content)
              pattern-re (re-pattern (if (:case-insensitive opts)
                                       (str "(?i)" pattern)
                                       pattern))
              matches (keep-indexed
                       (fn [idx line]
                         (when (re-find pattern-re line)
                           {:line-num (inc idx)
                            :content line}))
                       lines)]
          (when (seq matches)
            {:jar jar-path
             :entry entry-path
             :matches matches}))))
    (catch Exception e
      (log/debug "Error searching" entry-path "in" jar-path ":" (.getMessage e))
      nil)))

(defn deps-grep
  "Search for a pattern in dependency jars.

   Arguments:
   - project-dir: Directory containing deps.edn
   - pattern: Regex pattern to search for
   - opts: Map of options
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

   Returns a map with :results and optionally :truncated"
  [project-dir pattern opts]
  (let [jars (cached-classpath-jars project-dir)
        {:keys [output-mode head-limit]
         :or {output-mode :content}} opts]
    (if-not jars
      {:error "Failed to resolve classpath. Is this a deps.edn project?"}
      (let [all-results (atom [])
            result-count (atom 0)
            limit-reached (atom false)]
        ;; Search each jar
        (doseq [jar jars
                :while (not @limit-reached)]
          (when-let [entries (list-jar-entries jar)]
            (let [filtered-entries (filter-entries entries opts)]
              (doseq [entry filtered-entries
                      :while (not @limit-reached)]
                (when-let [match (search-jar-entry-simple jar entry pattern opts)]
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
          @limit-reached (assoc :truncated true))))))
