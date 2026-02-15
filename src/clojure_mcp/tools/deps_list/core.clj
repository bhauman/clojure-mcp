(ns clojure-mcp.tools.deps-list.core
  "Core implementation for listing project dependencies.
   Resolves the classpath and parses Maven coordinates from jar paths."
  (:require
   [clojure.string :as str]
   [clojure-mcp.tools.deps-grep.core :as deps-grep]
   [clojure-mcp.tools.deps-sources.core :as deps-sources]))

(defn deps-list
  "List all dependencies on the classpath with their Maven coordinates.

   Arguments:
   - project-dir: Directory containing deps.edn
   - opts: Map of options
     :pattern - Optional regex pattern to filter dependencies (matches against group/artifact)

   Returns a map with :dependencies (sorted list of {:group :artifact :version})
   or :error on failure."
  [project-dir opts]
  (let [jars (deps-grep/cached-base-jars project-dir)]
    (if-not jars
      {:error "Failed to resolve classpath. Is this a deps.edn project?"}
      (let [pattern-re (when-let [p (:pattern opts)]
                         (re-pattern (str "(?i)" p)))
            deps (->> jars
                      (keep deps-sources/parse-maven-coords)
                      (map (fn [{:keys [group artifact version]}]
                             {:group group
                              :artifact artifact
                              :version version}))
                      (filter (fn [{:keys [group artifact]}]
                                (if pattern-re
                                  (or (re-find pattern-re group)
                                      (re-find pattern-re artifact))
                                  true)))
                      (sort-by (juxt :group :artifact))
                      vec)]
        {:dependencies deps}))))
