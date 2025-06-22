(ns clojure-mcp.tools.unified-read-file.file-timestamps
  "Functionality for tracking file read timestamps and checking modifications."
  (:require
   [clojure-mcp.tools.unified-read-file.core :as core]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]))

(defn get-file-timestamps
  "Gets the current file timestamps map from the nrepl-client.
   Returns an empty map if no timestamps exist yet."
  [nrepl-client-atom]
  (get @nrepl-client-atom ::file-timestamps {}))

(defn update-file-timestamp!
  "Updates the timestamp for a file in the nrepl-client-atom.
   Uses canonical paths to ensure consistent file identification.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client
   - file-path: Path to the file
   - timestamp: Timestamp to store (defaults to current time)"
  ([nrepl-client-atom file-path]
   (update-file-timestamp! nrepl-client-atom file-path (System/currentTimeMillis)))
  ([nrepl-client-atom file-path timestamp]
   (log/debug "Updating timestamp for file:" file-path "to:" timestamp)
   (let [canonical-path (.getCanonicalPath (io/file file-path))]
     (assert (.isAbsolute (io/file canonical-path))
             (str "Path must be absolute, got: " canonical-path))
     (log/debug "Using canonical path:" canonical-path)
     (swap! nrepl-client-atom update ::file-timestamps
            (fn [timestamps] (assoc timestamps canonical-path timestamp)))
     (log/info "Updated timestamp for:" canonical-path "to:" timestamp))))

(defn update-file-timestamp-to-current-mtime!
  "Updates the timestamp for a file using its current modification time.
   Uses canonical paths to ensure consistent file identification.
   This is useful after writing to a file to ensure the timestamp
   matches exactly what the filesystem reports.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client
   - file-path: Path to the file
   
   Returns true if successful, false if the file doesn't exist."
  [nrepl-client-atom file-path]
  (log/debug "Updating timestamp to current mtime for file:" file-path)
  (let [canonical-path (.getCanonicalPath (io/file file-path))
        canonical-file (io/file canonical-path)]
    (if (.exists canonical-file)
      (let [current-mtime (.lastModified canonical-file)]
        (log/debug "File exists, canonical path:" canonical-path "mtime:" current-mtime)
        (update-file-timestamp! nrepl-client-atom canonical-path current-mtime))
      (do
        (log/warn "File does not exist:" file-path)
        false))))

(defn file-modified-since-read?
  "Checks if a file has been modified since it was last read.
   Uses canonical paths to ensure consistent file identification.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client
   - file-path: Path to the file
   
   Returns:
   - true if the file has been modified since last read or was never read
   - false if the file hasn't been modified since last read"
  [nrepl-client-atom file-path]
  (log/debug "Checking if file modified since read:" file-path)
  (let [canonical-path (.getCanonicalPath (io/file file-path))
        canonical-file (io/file canonical-path)]
    (if (.exists canonical-file)
      (let [timestamps (get-file-timestamps nrepl-client-atom)
            last-read-time (or (get timestamps canonical-path) 0)]
        (log/debug "Canonical path:" canonical-path "last-read-time:" last-read-time)
        (let [current-mtime (.lastModified canonical-file)
              modified? (> current-mtime last-read-time)]
          (log/debug "File mtime:" current-mtime "modified?:" modified?)
          modified?))
      (do
        (log/debug "File does not exist, considering as modified")
        true))))

(defn read-file-with-timestamp
  "Reads a file and updates the timestamp in the nrepl-client-atom if provided.
   Uses canonical paths to ensure consistent file identification.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client (can be nil)
   - path: Path to the file
   - offset: Line number to start reading from (0-indexed, default 0)
   - limit: Maximum number of lines to read (default 2000)
   - max-line-length: Maximum length per line before truncation (default 1000)
   
   Returns the result from core/read-file and updates the timestamp if nrepl-client-atom is provided."
  [nrepl-client-atom path offset limit & {:keys [max-line-length] :or {max-line-length 1000}}]
  (log/debug "Reading file with timestamp:" path "offset:" offset "limit:" limit)
  (let [canonical-path (.getCanonicalPath (io/file path))
        result (core/read-file path offset limit :max-line-length max-line-length)]
    ;; Only update timestamp if the read was successful and we have a client atom
    (when (and nrepl-client-atom (not (:error result)))
      (log/debug "Read successful, updating timestamp")
      (update-file-timestamp-to-current-mtime! nrepl-client-atom canonical-path))
    (log/info "Read file:" canonical-path "success:" (not (:error result)))
    result))

(defn list-tracked-files
  "Returns a list of all files that have timestamps recorded.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client
   
   Returns a sequence of file paths."
  [nrepl-client-atom]
  (keys (get-file-timestamps nrepl-client-atom)))

(defn list-modified-files
  "Returns a list of all tracked files that have been modified since last read.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client
   
   Returns a sequence of foile paths for modified files."
  [nrepl-client-atom]
  (let [tracked-files (list-tracked-files nrepl-client-atom)]
    (filter #(file-modified-since-read? nrepl-client-atom %)
            tracked-files)))
