(ns clojure-mcp.nrepl-launcher
  "Handles automatic nREPL server startup and port discovery."
  (:require [clojure-mcp.utils.valid-paths :as valid-paths]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io BufferedReader]
           [java.util.concurrent TimeUnit]))

(defn parse-port-from-output
  "Parse nREPL port number from process output.
   Looks for common patterns like 'port 12345', ':12345', 'nREPL server started on port 12345'.
   Returns the port number as an integer or nil if not found."
  [output]
  (when output
    (let [patterns [#"(?i)\bport\s{1,10}(\d{4,5})\b" ; port 12345
                    #":\s?(\d{4,5})\b" ; :12345 or : 12345 (4-5 digit ports)
                    #"(?i)Started on\s{1,10}(\d{4,5})\b" ; Started on 12345
                    #"(?i)Listening on\s{1,10}(\d{4,5})\b"] ; Listening on 12345
          line (str/trim output)]
      (some (fn [pattern]
              (when-let [match (re-find pattern line)]
                (when-let [port-str (second match)]
                  (try
                    (let [port (Integer/parseInt port-str)]
                      (when (<= 1024 port 65535) ; Valid port range
                        port))
                    (catch NumberFormatException _ nil)))))
            patterns))))

;;; Process cleanup functions

(defn destroy-nrepl-process
  "Safely destroy an nREPL process if it's still alive."
  [^Process process]
  (when (and process (.isAlive process))
    (try
      (log/info "Terminating auto-started nREPL process")
      (.destroy process)
      ;; Give it a moment to shutdown gracefully
      (if (.waitFor process 3 TimeUnit/SECONDS)
        (log/info "nREPL process terminated gracefully")
        ;; Process didn't terminate within timeout - force kill
        (do
          (log/warn "nREPL process did not terminate within timeout, forcing termination")
          (try
            (.destroyForcibly process)
            (.waitFor process 2 TimeUnit/SECONDS)
            (log/info "nREPL process force-terminated")
            (catch Exception e
              (log/error e "Failed to force-terminate nREPL process")))))
      (catch Exception e
        (log/warn e "Error while terminating nREPL process")
        ;; Force kill if graceful termination failed
        (try
          (.destroyForcibly process)
          (log/info "nREPL process force-terminated")
          (catch Exception e2
            (log/error e2 "Failed to force-terminate nREPL process")))))))

(defn setup-process-cleanup
  "Configure automatic cleanup for nREPL process on JVM shutdown."
  [^Process process]
  (when process
    ;; Add shutdown hook
    (try
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. #(destroy-nrepl-process process)))
      (log/debug "Added shutdown hook for nREPL process cleanup")
      (catch Exception e
        (log/warn e "Failed to add shutdown hook for process cleanup")))
    process))

(defn wait-for-port
  "Monitor process stdout for port information.
   Returns port number when found or nil on timeout.
   Uses BufferedReader.ready() for proper non-blocking character reads."
  [^Process process timeout-ms parse-port?]
  (if-not parse-port?
    (do
      (log/info "Port parsing disabled, skipping port discovery")
      nil)
    (let [^BufferedReader reader (io/reader (.getInputStream process))
          start-time (System/currentTimeMillis)
          buffer (StringBuilder.)
          char-buffer (char-array 1024)]
      (try
        (loop []
          (when (< (- (System/currentTimeMillis) start-time) timeout-ms)
            ;; Check if character data is ready to read (non-blocking)
            (if (.ready reader)
              ;; Read available characters into buffer
              (let [chars-read (.read reader char-buffer)]
                (when (> chars-read 0)
                  (.append buffer char-buffer 0 chars-read)
                  (log/debug "Read" chars-read "chars, buffer now contains:" (str buffer))
                  ;; Check accumulated buffer for port
                  (if-let [port (parse-port-from-output (str buffer))]
                    (do
                      (log/info (str "Discovered nREPL port: " port))
                      port)
                    ;; No port found yet, continue accumulating
                    (do
                      (Thread/sleep 10)
                      (recur)))))
              ;; No character data ready, check if process is still alive
              (if (.isAlive process)
                (do
                  (Thread/sleep 100)
                  (recur))
                ;; Process exited, check final buffer content
                (if-let [port (parse-port-from-output (str buffer))]
                  (do
                    (log/info (str "Discovered nREPL port from final buffer: " port))
                    port)
                  (do
                    (log/warn "Process exited before port was discovered")
                    nil))))))
        (catch Exception e
          (log/error e "Error reading process output")
          nil)
        (finally
          (try
            (.close reader)
            (catch Exception _)))))))

(defn start-nrepl-process
  "Start an nREPL server process using the provided command.
   Returns a map with :port (if discovered or provided) and :process.
   If :port is provided, uses it. Otherwise, parses port from output."
  [{:keys [start-nrepl-cmd project-dir port]}]
  (log/info (str "Starting nREPL with command: " start-nrepl-cmd))
  (log/info (str "Working directory: " project-dir))
  (try
    (let [pb-opts (cond-> {}
                    project-dir (assoc :dir (io/file project-dir)))
          process (apply process/start pb-opts start-nrepl-cmd)
          ;; If port not provided, parse from output. Otherwise use provided port.
          discovered-port (if port
                            port ;; Use provided port
                            (wait-for-port process 30000 true))] ;; Parse from output
      (if (not discovered-port)
        (do
          (log/error "Failed to discover nREPL port from process output")
          (when (.isAlive process)
            (log/info "Terminating nREPL process since port discovery failed")
            (.destroy process))
          (throw (ex-info "Failed to discover nREPL port"
                          {:command start-nrepl-cmd
                           :project-dir project-dir})))
        (do
          (log/info (str "nREPL server started successfully on port " discovered-port))
          ;; Setup automatic cleanup for the process
          (setup-process-cleanup process)
          {:port discovered-port
           :process process})))
    (catch Exception e
      (log/error e "Failed to start nREPL process")
      (throw (ex-info "Failed to start nREPL process"
                      {:command start-nrepl-cmd
                       :project-dir project-dir
                       :error (.getMessage e)}
                      e)))))

(defn load-config-if-exists
  "Load .clojure-mcp/config.edn if it exists in the current directory or project-dir."
  [project-dir]
  (let [config-locations (filterv
                          some?
                          [(when project-dir
                             (io/file project-dir ".clojure-mcp" "config.edn"))
                           (io/file ".clojure-mcp" "config.edn")])
        config-file (first (filterv valid-paths/path-exists? config-locations))]
    (when config-file
      (log/debug (str "Loading config from: " config-file))
      (try
        (with-open [r (io/reader config-file)]
          (edn/read (java.io.PushbackReader. r)))
        (catch Exception e
          (log/warn e (str "Failed to load config from " config-file))
          nil)))))

(defn should-start-nrepl?
  "Determine if we should auto-start an nREPL server based on conditions:
   1. CLI: start-nrepl-cmd AND project-dir provided in args
   2. Config: .clojure-mcp/config.edn exists with :start-nrepl-cmd
   If :port is provided with :start-nrepl-cmd, uses fixed port instead of parsing."
  [nrepl-args]
  (let [{:keys [start-nrepl-cmd project-dir port]} nrepl-args]
    (cond
      ;; Don't start if port provided but no start command (connecting to existing nREPL)
      (and port (not start-nrepl-cmd))
      (do
        (log/debug "Port provided without start command, connecting to existing nREPL")
        false)

      ;; CLI condition: start-nrepl-cmd AND project-dir provided
      (and start-nrepl-cmd project-dir)
      (do
        (log/info "Auto-start condition met: CLI args provided")
        true)

      ;; Config file condition
      :else
      (if-let [config (load-config-if-exists project-dir)]
        (if (:start-nrepl-cmd config)
          (do
            (log/info "Auto-start condition met: config file with :start-nrepl-cmd")
            true)
          false)
        false))))

(defn add-project-dir [{:keys [start-nrepl-cmd project-dir] :as nrepl-args}]
  (if (and start-nrepl-cmd (not project-dir))
    (assoc nrepl-args :project-dir (System/getProperty "user.dir"))
    nrepl-args))

(defn maybe-start-nrepl-process
  "Main wrapper that conditionally starts an nREPL process.
   Returns updated nrepl-args with discovered :port if process was started,
   otherwise returns nrepl-args unchanged.
   When :port is provided with :start-nrepl-cmd, uses that fixed port.
   When :port is not provided, parses port from command output."
  [nrepl-args]
  (let [nrepl-args' (add-project-dir nrepl-args)]
    (if (should-start-nrepl? nrepl-args')
      (let [;; Load config and merge with CLI args (CLI takes precedence)
            config (load-config-if-exists (:project-dir nrepl-args'))
            merged-args (merge config nrepl-args')
            {:keys [start-nrepl-cmd]} merged-args]
        (log/info "Starting nREPL process automatically")
        (let [{:keys [port process]} (start-nrepl-process merged-args)]
          (if port
            (do
              (log/info (str "Using port: " port))
              (assoc nrepl-args :port port :nrepl-process process))
            ;; This shouldn't happen as start-nrepl-process throws on failure
            (throw (ex-info "nREPL process started but no port available"
                            {:start-nrepl-cmd start-nrepl-cmd})))))
      (do
        (log/debug "Auto-start conditions not met, using existing behavior")
        nrepl-args))))
