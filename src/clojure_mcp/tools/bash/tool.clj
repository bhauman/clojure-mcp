(ns clojure-mcp.tools.bash.tool
  "Implementation of the bash tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.config :as config]
   [clojure-mcp.nrepl :as nrepl]
   [clojure-mcp.utils.valid-paths :as valid-paths]
   [clojure-mcp.tools.bash.core :as core]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Factory function to create the tool configuration
(defn create-bash-tool
  "Creates the bash tool configuration.
   Checks for :tools-config {:bash {:default-timeout-ms ...}} in the nrepl-client-atom
   and uses configured defaults.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (let [nrepl-client @nrepl-client-atom
        ;; Default timeout: 3 minutes (180000ms)
        defaults {:timeout_ms 180000}
        ;; Get tool-specific config
        tool-config (config/get-tool-config nrepl-client :bash)
        ;; Check bash-over-nrepl from tool config or global config
        bash-over-nrepl (get tool-config :bash-over-nrepl
                             (config/get-bash-over-nrepl nrepl-client))
        session-type (when bash-over-nrepl :tools)
        ;; Extract default-timeout-ms from config and rename it
        timeout_ms (or (:default-timeout-ms tool-config)
                       (:timeout_ms defaults))]
    (merge
     {:working-dir (config/get-nrepl-user-dir nrepl-client)
      :timeout_ms timeout_ms}
     (dissoc tool-config :default-timeout-ms) ; Remove config key
     {:tool-type :bash
      :nrepl-client-atom nrepl-client-atom
      :nrepl-session-type session-type})))

;; Implement the required multimethods for the bash tool
(defmethod tool-system/tool-name :bash [_]
  "bash")

(defmethod tool-system/tool-description :bash [_]
  "Execute bash shell commands on the host system.

Examples:
1. List files: bash(command: \"ls -la\")
2. Find text in files: bash(command: \"grep -r 'pattern' /path/to/search\")
3. With working directory: bash(command: \"ls -la\", working_directory: \"/tmp\")
4. With timeout: bash(command: \"sleep 10\", timeout_ms: 5000)
5. Git commands 

For long running processes like running tests increase the timeout_ms so that the process can complete.

Note: Non-zero exit codes are NOT treated as tool errors - check exit_code
in the response to determine command success.")

(defmethod tool-system/tool-schema :bash [_]
  {:type :object
   :properties {:command {:type :string
                          :description "The shell command to execute"}
                :working_directory {:type :string
                                    :description "Directory to run the command in (optional)"}
                :timeout_ms {:type :integer
                             :description "Maximum execution time in milliseconds (optional, default: 180000)"}}
   :required [:command]})

(defmethod tool-system/validate-inputs :bash [{:keys [nrepl-client-atom working-dir] :as tool} inputs]
  (let [{:keys [command working_directory timeout_ms]} inputs
        nrepl-client @nrepl-client-atom
        ;; Use configured defaults if not provided in inputs
        working_directory (or working_directory
                              working-dir)
        ;; Use input timeout, fall back to tool's configured default
        timeout_ms (or timeout_ms
                       (:timeout_ms tool))]
    ;; Validate required parameters
    (when-not command
      (throw (ex-info "Missing required parameter: command"
                      {:inputs inputs})))
    (assert working_directory)
    (let [validated-dir (valid-paths/validate-path-with-client working_directory nrepl-client)
          dir (io/file validated-dir)]
      (when-not (.exists dir)
        (throw (ex-info (str "Working directory does not exist: " validated-dir)
                        {:inputs inputs
                         :error-details (str "Directory not found: " validated-dir)})))
      (when-not (.isDirectory dir)
        (throw (ex-info (str "Path is not a directory: " validated-dir)
                        {:inputs inputs
                         :error-details (str "Not a directory: " validated-dir)})))

      ;; Validate timeout if provided
      (when (and timeout_ms (or (not (number? timeout_ms))
                                (< timeout_ms 1)))
        (throw (ex-info "Invalid timeout value"
                        {:inputs inputs
                         :error-details "Timeout must be a positive number"})))

      ;; Validate all filesystem paths in the bash command
      (let [allowed-dirs (config/get-allowed-directories nrepl-client)]
        (try
          (valid-paths/validate-bash-command-paths command validated-dir allowed-dirs)
          (catch Exception e
            (throw (ex-info (str "Bash command contains invalid paths: " (.getMessage e))
                            {:inputs inputs
                             :command command
                             :error-details (.getMessage e)
                             :caused-by (.getData e)})))))

      ;; Return validated and normalized inputs
      (cond-> {:command command}
        working_directory (assoc :working-directory validated-dir)
        timeout_ms (assoc :timeout-ms timeout_ms)))))

(defmethod tool-system/execute-tool :bash [{:keys [nrepl-client-atom nrepl-session-type]} inputs]
  (let [nrepl-client @nrepl-client-atom
        working-dir (config/get-nrepl-user-dir nrepl-client)
        effective-port (or (:port nrepl-client)
                           (nrepl/read-nrepl-port-file working-dir))]
    (if (and nrepl-session-type effective-port)
      ;; Execute over nREPL with session type - pass the resolved port
      (core/execute-bash-command-nrepl nrepl-client-atom
                                       (assoc inputs
                                              :session-type nrepl-session-type
                                              :port effective-port))
      ;; Execute locally (fallback when no port available)
      (core/execute-bash-command nrepl-client inputs))))

(defmethod tool-system/format-results :bash [_ result]
  (let [{:keys [stdout stderr exit-code timed-out error]} result
        formatted-output (cond-> []
                           error (conj (str "Error: " error))
                           :always (conj (str "Exit code: " exit-code
                                              (when timed-out " (operation timed out, if this is a long running process like tests increase the timeout_ms)")))
                           (not (str/blank? stdout)) (conj (str "Standard output:\n" stdout))
                           (not (str/blank? stderr)) (conj (str "Standard error:\n" stderr)))]

    ;; We treat command execution as successful even if the command itself returns non-zero
    ;; Only report error for problems with the tool itself
    {:result formatted-output
     :error (boolean error)}))

;; Backward compatibility function that returns the registration map
(defn bash-tool
  "Returns the registration map for the bash tool.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-bash-tool nrepl-client-atom)))
