(ns clojure-mcp.tools.scratch-pad.config
  "Configuration file management for scratch pad persistence"
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]))

(defn read-config-file
  "Reads the config.edn file from the working directory.
   Returns an empty map if file doesn't exist or can't be read."
  [working-directory]
  (let [config-file (io/file working-directory ".clojure-mcp" "config.edn")]
    (try
      (if (.exists config-file)
        (edn/read-string (slurp config-file))
        {})
      (catch Exception e
        (log/warn e "Failed to read config.edn")
        {}))))

(defn write-config-file
  "Writes the config map to config.edn file in the working directory.
   Creates .clojure-mcp directory if it doesn't exist."
  [working-directory config-map]
  (let [dir (io/file working-directory ".clojure-mcp")
        config-file (io/file dir "config.edn")]
    (try
      ;; Ensure directory exists
      (when-not (.exists dir)
        (.mkdirs dir))
      ;; Write config with pretty printing
      (spit config-file (with-out-str (clojure.pprint/pprint config-map)))
      (log/info "Updated config.edn" {:path (.getPath config-file)})
      true
      (catch Exception e
        (log/error e "Failed to write config.edn")
        false))))

(defn update-scratch-pad-config
  "Updates the scratch pad configuration in config.edn.
   Preserves other configuration values."
  [working-directory enabled filename]
  (let [current-config (read-config-file working-directory)
        updated-config (cond-> current-config
                         (some? enabled)
                         (assoc :scratch-pad-load enabled)
                         (some? filename)
                         (assoc :scratch-pad-file filename))]
    (write-config-file working-directory updated-config)))