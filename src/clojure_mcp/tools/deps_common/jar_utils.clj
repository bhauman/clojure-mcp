(ns clojure-mcp.tools.deps-common.jar-utils
  "Pure Java utilities for reading jar/zip files.
   Provides cross-platform alternatives to shelling out to `unzip`."
  (:require
   [clojure.java.io :as io]
   [taoensso.timbre :as log])
  (:import
   (java.util.zip ZipFile ZipException)))

(defn list-jar-entries
  "List all entries in a jar file using java.util.zip.ZipFile.
   Returns a vector of entry path strings, or nil on error."
  [jar-path]
  (try
    (with-open [zf (ZipFile. (str jar-path))]
      (let [entries (enumeration-seq (.entries zf))]
        (mapv #(.getName %) entries)))
    (catch ZipException e
      (log/debug "Failed to read jar (ZipException):" jar-path (.getMessage e))
      nil)
    (catch java.io.FileNotFoundException e
      (log/debug "Jar file not found:" jar-path (.getMessage e))
      nil)
    (catch Exception e
      (log/debug "Failed to list jar entries:" jar-path (.getMessage e))
      nil)))

(defn read-jar-entry
  "Read a single entry from a jar file as a string using java.util.zip.ZipFile.
   Returns the content string, or nil if the entry is not found or on error."
  [jar-path entry-path]
  (try
    (with-open [zf (ZipFile. (str jar-path))]
      (when-let [entry (.getEntry zf entry-path)]
        (with-open [is (.getInputStream zf entry)]
          (slurp is))))
    (catch ZipException e
      (log/debug "Failed to read jar entry (ZipException):" entry-path "in" jar-path (.getMessage e))
      nil)
    (catch java.io.FileNotFoundException e
      (log/debug "Jar file not found:" jar-path (.getMessage e))
      nil)
    (catch Exception e
      (log/debug "Failed to read jar entry:" entry-path "in" jar-path (.getMessage e))
      nil)))
