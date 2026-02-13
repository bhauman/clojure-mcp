(ns clojure-mcp.tools.nrepl-ports.core
  "Core implementation for nREPL port discovery.
   Discovers running nREPL servers by checking .nrepl-port files
   and scanning for JVM processes listening on TCP ports."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [nrepl.core :as nrepl]))

(defn read-nrepl-port-file
  "Read port number from .nrepl-port file in the given directory.
   Returns nil if file doesn't exist or on error."
  [dir]
  (try
    (let [port-file (io/file dir ".nrepl-port")]
      (when (.exists port-file)
        (parse-long (str/trim (slurp port-file :encoding "UTF-8")))))
    (catch Exception _
      nil)))

(defn parse-lsof-ports
  "Parse port numbers from lsof output.
   Matches patterns like: 'TCP *:7888 (LISTEN)' or 'TCP 127.0.0.1:7889 (LISTEN)'"
  [lsof-output]
  (when lsof-output
    (->> (str/split-lines lsof-output)
         (keep (fn [line]
                 (when-let [[_ port] (re-find #"TCP\s+(?:\*|[\d.]+):(\d+)\s+\(LISTEN\)" line)]
                   (parse-long port))))
         distinct
         vec)))

(defn get-listening-jvm-ports
  "Find ports where Java/Clojure/Babashka processes are listening.
   Returns vector of port numbers, empty vector on error."
  []
  (try
    (let [proc (.. (ProcessBuilder. ["sh" "-c" "lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | grep -Ei 'java|clojure|babashka|bb|nrepl'"])
                   (redirectErrorStream true)
                   start)
          _ (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
          output (slurp (.getInputStream proc))]
      (or (parse-lsof-ports output) []))
    (catch Exception _
      [])))

(defn detect-nrepl-env-type
  "Detect the environment type from nREPL describe response.
   Returns :clj, :bb, :basilisp, :scittle, :shadow, or :unknown."
  [describe-resp]
  (let [versions (:versions describe-resp)
        aux-ns (get-in describe-resp [:aux :current-ns])]
    (cond
      (contains? versions :babashka) :bb
      (contains? versions :basilisp) :basilisp
      (contains? versions :sci-nrepl) :scittle
      ;; Check for shadow-cljs (runs on top of Clojure, has shadow.user ns)
      (and (contains? versions :clojure) (= aux-ns "shadow.user")) :shadow
      (contains? versions :clojure) :clj
      :else :unknown)))

(defn get-project-dir-expr
  "Get the expression to evaluate to find the project directory based on env type."
  [env-type]
  (case env-type
    :clj "(System/getProperty \"user.dir\")"
    :bb "(System/getProperty \"user.dir\")"
    :basilisp "(import os)\n(os/getcwd)"
    :scittle nil
    :shadow "(System/getProperty \"user.dir\")"
    nil))

(defn gather-port-info
  "Gather information about a nREPL port using a single connection.
   Returns map with port info or nil if connection fails."
  [host port source current-dir]
  (try
    (let [conn (nrepl/connect :host host :port port)
          client (nrepl/client conn 1000)]
      (try
        ;; Try to get sessions to validate this is a real nREPL
        (let [sessions-resp (first (nrepl/message client {:op "ls-sessions"}))
              sessions (:sessions sessions-resp)]
          (if sessions
            ;; Valid nREPL - get env type and project dir
            (let [describe-resp (first (nrepl/message client {:op "describe"}))
                  env-type (detect-nrepl-env-type describe-resp)
                  dir-expr (get-project-dir-expr env-type)
                  project-dir (when dir-expr
                                (try
                                  (let [eval-resp (nrepl/message client {:op "eval" :code dir-expr})
                                        value-resp (first (filter :value eval-resp))]
                                    (when-let [v (:value value-resp)]
                                      ;; Strip quotes if present
                                      (str/replace v #"^\"|\"$" "")))
                                  (catch Exception _ nil)))
                  matches-cwd (and project-dir
                                   (= project-dir current-dir))]
              {:host host
               :port port
               :source source
               :valid true
               :session-count (count sessions)
               :env-type env-type
               :project-dir project-dir
               :matches-cwd matches-cwd})
            ;; Invalid nREPL
            {:host host
             :port port
             :source source
             :valid false
             :session-count 0
             :env-type nil
             :project-dir nil
             :matches-cwd false}))
        (finally
          (.close conn))))
    (catch Exception e
      (log/debug "Failed to connect to" host ":" port "-" (.getMessage e))
      ;; Connection failed
      {:host host
       :port port
       :source source
       :valid false
       :session-count 0
       :env-type nil
       :project-dir nil
       :matches-cwd false})))

(defn discover-nrepl-ports
  "Discover potential nREPL ports by:
   1. Checking .nrepl-port file in current directory
   2. Finding Java/Clojure/Babashka processes listening on TCP ports (lsof)
   3. Validating discovered ports by checking if they respond to ls-sessions
   4. Detecting environment type (clj, bb, basilisp, shadow, etc.)
   5. Getting project directory and checking if it matches current working directory

   Arguments:
   - current-dir: The directory to use as current working directory for matching

   Returns vector of maps with:
   - :host - Host string (always \"localhost\")
   - :port - Port number
   - :source - How port was discovered (:nrepl-port-file or :lsof)
   - :valid - Boolean indicating if port responds to nREPL ls-sessions op
   - :env-type - Environment type (:clj, :bb, :basilisp, :scittle, :shadow, :unknown, or nil if invalid)
   - :project-dir - Project directory path from nREPL server (or nil)
   - :matches-cwd - Boolean indicating if project-dir matches current working directory"
  [current-dir]
  (let [;; Collect port candidates
        port-file-port (read-nrepl-port-file current-dir)
        lsof-ports (get-listening-jvm-ports)

        ;; Combine and deduplicate
        all-ports (distinct (concat (when port-file-port [port-file-port])
                                    lsof-ports))

        ;; Validate each port and gather info in parallel using a single connection per port
        results (pmap (fn [port]
                        (let [source (if (= port port-file-port) :nrepl-port-file :lsof)]
                          (gather-port-info "localhost" port source current-dir)))
                      all-ports)]
    (vec results)))
