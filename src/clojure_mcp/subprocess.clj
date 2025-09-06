(ns clojure-mcp.subprocess
  "Subprocess execution and nREPL port discovery for :start-nrepl-cmd feature."
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as process]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

(defn parse-port-from-output
  "Extract the first valid nREPL port number from command output.

   Supports common nREPL output formats:
   - 'nREPL server started on port 7888'
   - 'Started nREPL on port: 7888'
   - 'Port: 7888'
   - Standalone numbers: '7888'

   Returns the port number as an integer, or nil if no valid port found.
   Valid ports are in range 1024-65535."
  [output]
  (let [s             (str output)
        valid-port?   (fn [n] (and (<= 1024 n) (<= n 65535)))
        parse-int     (fn [^String x]
                        (try (Integer/parseInt x)
                             (catch NumberFormatException _ nil)))
        ;; Prefer explicit "port" or "nrepl" contexts
        patterns      [#"(?i)\bnrepl[^\n\r]*?\bport\b[^\d]{0,20}(\d{4,5})\b"
                       #"(?i)\bport\b[^\d]{0,20}(\d{4,5})\b"
                       #"(?i)\bnrepl[^\n\r]*?(\d{4,5})\b"]
        from-patterns (some (fn [re]
                              (when-let [[_ p] (re-find re s)]
                                (let [n (parse-int p)]
                                  (when (and n (valid-port? n)) n))))
                            patterns)]
    (or from-patterns
        ;; Fallback: choose the last plausible 4-5 digit number in range
        (some->> (re-seq #"\d{4,5}" s)
                 (map parse-int)
                 (filter some?)
                 (filter valid-port?)
                 last))))

(defn- read-available-output
  "Read available bytes from a stream without blocking.

   Parameters:
     stream - The input stream to read from

   Returns:
     String with available content, or nil if no content available"
  [stream]
  (when (and stream (> (.available stream) 0))
    (let [available-bytes (.available stream)
          buffer (byte-array (min available-bytes 1024))
          bytes-read (.read stream buffer 0 (min available-bytes 1024))]
      (when (> bytes-read 0)
        (String. buffer 0 bytes-read)))))

(defn- collect-process-output
  "Collect new output from process stdout and stderr streams.

   Parameters:
     process - The running process

   Returns:
     Map with :stdout and :stderr keys containing new output chunks"
  [^Process process]
  (let [stdout-chunk (read-available-output (process/stdout process))
        stderr-chunk (read-available-output (process/stderr process))]
    {:stdout stdout-chunk :stderr stderr-chunk}))

(defn- process-terminated?
  "Check if process has terminated and no more output is available.

   Parameters:
     process - The running process

   Returns:
     True if process is dead and streams are empty"
  [^Process process]
  (and (not (.isAlive process))
       (zero? (.available (process/stdout process)))
       (zero? (.available (process/stderr process)))))

(defn wait-for-port-in-output
  "Monitor process output streams and return first discovered port.

   Parameters:
     process - The running process
     timeout-ms - Maximum time to wait in milliseconds

   Returns:
     {:port port-number} on success
     {:error error-message} on failure"
  [^Process process timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [collected-stdout "" collected-stderr ""]
      (let [now (System/currentTimeMillis)]
        (cond
          ;; Timeout exceeded
          (> now deadline)
          {:error
           (str "Timeout after " timeout-ms "ms waiting for nREPL port.\n"
                "STDOUT: " collected-stdout "\n"
                "STDERR: " collected-stderr)}

          ;; Process terminated before finding port
          (process-terminated? process)
          (let [exit-code (.exitValue process)]
            {:error (str "Process terminated with exit code " exit-code
                         " before nREPL port was found.\n"
                         "STDOUT: " collected-stdout "\n"
                         "STDERR: " collected-stderr)})

          ;; Check for port in available output
          :else
          (let [{:keys [stdout stderr]} (collect-process-output process)
                updated-stdout (str collected-stdout stdout)
                updated-stderr (str collected-stderr stderr)]
            (if-let [port (and (seq stdout)
                               (parse-port-from-output updated-stdout))]
              {:port port}
              (do
                (Thread/sleep 100)
                (recur updated-stdout updated-stderr)))))))))

(defn- shell-cmd [cmd]
  (if (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
    ["cmd" "/c" cmd]
    ["sh" "-c" cmd]))

(defn start-nrepl-cmd
  "Execute the :start-nrepl-cmd and return the running process.

   This starts the command but does not attempt to parse port from output.
   Used when port will be read from .nrepl-port file instead.

   Parameters:
     cmd - Shell command string to execute

   Returns:
     The running Process object

   Throws:
     Exception on command execution failure"
  [cmd]
  (log/info "Starting :start-nrepl-cmd:" cmd)
  (try
    (apply process/start {:out :pipe :err :pipe} (shell-cmd cmd))
    (catch Exception e
      (log/error e "Failed to start :start-nrepl-cmd:" cmd)
      (throw (ex-info "Command execution failed"
                      {:cmd cmd
                       :cause (.getMessage e)}
                      e)))))

(defn start-nrepl-cmd-and-parse-port
  "Start nREPL command and discover the port by parsing stdout.

   This function starts the nREPL command and monitors its output streams
   to discover the port number that the server announces.

   Parameters:
     cmd - Shell command string to execute
     timeout-ms - Timeout in milliseconds (default 30000)

   Returns:
     Port number on success

   Throws:
     Exception on any failure (command execution, timeout, port parsing)"
  ([cmd] (start-nrepl-cmd-and-parse-port cmd 30000))
  ([cmd timeout-ms]
   (log/info "Starting nREPL command with port parsing:" cmd)
   (try
     (let [process (start-nrepl-cmd cmd)
           result (wait-for-port-in-output process timeout-ms)]

       (cond
         (:port result)
         (do
           (log/info "Discovered nREPL port:" (:port result))
           (:port result))

         (:error result)
         (do
           (log/error "Failed to discover nREPL port:" (:error result))
           (throw (ex-info "nREPL port discovery failed"
                           {:cmd cmd
                            :error (:error result)})))

         :else
         (throw (ex-info "Unknown error during port discovery"
                         {:cmd cmd}))))

     (catch Exception e
       (log/error e "Failed to start nREPL command and parse port:" cmd)
       (throw (ex-info "Command execution failed"
                       {:cmd cmd
                        :cause (.getMessage e)}
                       e))))))

(defn ^:dynamic *nrepl-port-file-path*
  "Returns the path to the .nrepl-port file.
   This function can be rebound in tests to control the file location."
  []
  (let [working-dir (System/getProperty "user.dir")]
    (.getAbsolutePath (io/file working-dir ".nrepl-port"))))

(defn read-nrepl-port-file
  "Read nREPL port from .nrepl-port file in the current working directory.

   Returns:
     Port number on success

   Throws:
     Exception if file doesn't exist, is unreadable, or doesn't contain
     a valid port"
  []
  (let [port-file-path (*nrepl-port-file-path*)
        port-file (io/file port-file-path)]
    (log/info "Attempting to read nREPL port from:" port-file-path)
    (try
      (if (.exists port-file)
        (let [content (str/trim (slurp port-file))
              port (try
                     (Integer/parseInt content)
                     (catch NumberFormatException e
                       (throw (ex-info "Invalid port number in .nrepl-port file"
                                       {:file port-file-path
                                        :content content}
                                       e))))]
          (if (and (>= port 1024) (<= port 65535))
            (do
              (log/info "Successfully read nREPL port from file:" port)
              port)
            (throw (ex-info "Port number out of valid range (1024-65535)"
                            {:file port-file-path
                             :port port}))))
        (throw (ex-info ".nrepl-port file not found"
                        {:file port-file-path})))
      (catch Exception e
        (log/error e "Failed to read nREPL port from file:" port-file-path)
        (throw e)))))

(defn poll-for-nrepl-port-file
  "Poll for .nrepl-port file creation and read the port when available.

   This function waits for the file to be created (presumably by a running
   nREPL process) and then reads the port from it.

   Parameters:
     timeout-ms - Maximum time to wait in milliseconds (default 30000)
     start-time - Time when the process was started (for freshness check,
                  default current time)

   Returns:
     Port number on success

   Throws:
     Exception on timeout or file reading failure"
  ([]
   (poll-for-nrepl-port-file 30000 (System/currentTimeMillis)))
  ([timeout-ms]
   (poll-for-nrepl-port-file timeout-ms (System/currentTimeMillis)))
  ([timeout-ms start-time]
   (let [port-file-path (*nrepl-port-file-path*)
         port-file (io/file port-file-path)
         deadline (+ (System/currentTimeMillis) timeout-ms)]
     (log/info "Polling for .nrepl-port file creation")
     (loop []
       (cond
         ;; Timeout exceeded
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "Timeout waiting for .nrepl-port file creation"
                         {:timeout-ms timeout-ms
                          :file port-file-path}))

         ;; File exists and is fresh
         (and (.exists port-file) (>= (.lastModified port-file) start-time))
         (let [read-result (try
                             (read-nrepl-port-file)
                             (catch Exception e
                               ;; File might not be ready yet, signal retry
                               (log/warn e "Error reading .nrepl-port file, will retry")
                               ::retry))]
           (if (= read-result ::retry)
             (do
               (Thread/sleep 500)
               (recur))
             (do
               (log/info
                "Successfully read nREPL port from fresh file:" read-result)
               read-result)))

         ;; File doesn't exist or is stale, keep waiting
         :else
         (do
           (Thread/sleep 500)
           (recur)))))))

(defn start-nrepl-and-read-port-file
  "Start nREPL command and read port from .nrepl-port file with coordination.

   This function ensures we read a fresh port file written by our specific
   nREPL process by:
   1. Deleting any existing .nrepl-port file
   2. Starting the nREPL command
   3. Polling for the file to be recreated
   4. Reading the port from the fresh file

   Parameters:
     cmd - Shell command string to execute
     timeout-ms - Timeout in milliseconds (default 30000)

   Returns:
     Port number on success

   Throws:
     Exception on any failure"
  ([cmd] (start-nrepl-and-read-port-file cmd 30000))
  ([cmd timeout-ms]
   (let [port-file-path (*nrepl-port-file-path*)
         port-file (io/file port-file-path)
         start-time (System/currentTimeMillis)]

     (log/info "Coordinating :start-nrepl-cmd with :read-nrepl-port-file")

     ;; Step 1: Remove any existing .nrepl-port file
     (when (.exists port-file)
       (log/info "Removing existing .nrepl-port file:" port-file-path)
       (.delete port-file))

     ;; Step 2: Start the nREPL command
     (let [process (start-nrepl-cmd cmd)]
       (log/info "Started nREPL command, waiting for .nrepl-port file")

       ;; Step 3: Poll for fresh .nrepl-port file and read port
       (try
         (poll-for-nrepl-port-file timeout-ms start-time)
         (catch Exception e
           ;; If polling fails, try to clean up the process
           (try
             (.destroy ^Process process)
             (catch Exception cleanup-error
               (log/warn
                cleanup-error
                "Failed to cleanup process after polling failure")))
           (throw e)))))))
