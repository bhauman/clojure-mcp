(ns clojure-mcp.tools.bash.core
  "Core bash command execution functionality"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [taoensso.timbre :as log]
   [clojure-mcp.nrepl :as nrepl]
   [clojure-mcp.tools.eval.core :as eval-core])
  (:import
   (java.util.concurrent TimeUnit)
   (java.io InputStreamReader BufferedReader)))

;; 3 minutes? some test suites take much longer.
;; TODO this should go into the config.

;; this is pretty useless
(def ^:private disallowed-commands
  #{;; System modification commands
    "rm -rf" "rm -r" "rmdir" "dd" "mkfs" "format"
    ;; Network attack tools
    "nmap" "netcat" "nc"
    ;; User/permission commands
    "sudo" "su" "passwd" "chmod" "chown"
    ;; Dangerous system commands
    "shutdown" "reboot" "halt" "init"})

(defn- command-allowed?
  [command]
  (let [trimmed-command (str/trim command)]
    (not-any? #(str/starts-with? trimmed-command %) disallowed-commands)))

(defn- read-stream
  [stream]
  (with-open [reader (BufferedReader. (InputStreamReader. stream))]
    (str/join "\n" (line-seq reader))))

(defn- truncate-with-limit
  "Truncate a string if it exceeds the limit, adding a truncation message"
  [s limit]
  (if (> (count s) limit)
    (str (subs s 0 (- limit 16))
         "\n... (truncated)")
    s))

(defn execute-bash-command
  [_ {:keys [command working-directory timeout-ms] :as args}]
  (log/debug "Using local bash command: " command)
  (let [;; timeout-ms is now required - should be provided by tool
        _ (assert timeout-ms "timeout-ms is required")
        ;; Use same truncation limit as nREPL for consistency
        truncation-limit (int (* nrepl/truncation-length 0.85))
        max-stderr-length (quot truncation-limit 2)]
    (when-not (command-allowed? command)
      (throw (ex-info "Command not allowed due to security restrictions"
                      {:command command
                       :error-details "The command contains restricted operations"})))
    (log/debug "Executing bash command" command args)
    ;; Set up the ProcessBuilder
    (let [pb (ProcessBuilder. (into-array ["bash" "-c" command]))
          _ (when working-directory
              (.directory pb (io/file working-directory)))
          process (.start pb)]
      (if (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
        (let [;; Read stderr first with its limit
              stderr-content (read-stream (.getErrorStream process))
              stderr (truncate-with-limit stderr-content max-stderr-length)
              ;; Calculate remaining space for stdout
              remaining-space (max 500 (- truncation-limit 500 (count stderr)))
              stdout-content (read-stream (.getInputStream process))
              stdout (truncate-with-limit stdout-content remaining-space)]
          {:exit-code (.exitValue process)
           :stdout stdout
           :stderr stderr
           :timed-out false})
        (do
          (.destroyForcibly process)
          (let [;; For timeout case, still apply truncation
                stderr-content (try (read-stream (.getErrorStream process))
                                    (catch Exception _ ""))
                stderr (truncate-with-limit stderr-content max-stderr-length)
                remaining-space (max 500 (- truncation-limit 500 (count stderr)))
                stdout-content (try (read-stream (.getInputStream process))
                                    (catch Exception _ ""))
                stdout (truncate-with-limit stdout-content remaining-space)]
            {:stdout stdout
             :stderr stderr
             :exit-code -1
             :timed-out true}))))))

(defn generate-shell-eval-code
  "Generate Clojure code to execute shell command with explicit timeout handling"
  [command working-directory timeout-ms]
  ;; TODO add this require to an eval in the creation of this tool
  ;; actually we can ship this whole function to an eval in the creation
  ;; but this would survive a restart of the repl??
  ;; so we could think of this as memoization with the REPL as our store
  ;; 1. probe the repl to see if it has the tools we need
  ;; 2. if not eval the tools into the REPL env
  ;; 3. or use the tools
  ;; This takes 2 evals, is that words than just shipping the code?
  (format "(try
             (require 'clojure.java.io)
             (let [pb (ProcessBuilder. (into-array [\"bash\" \"-c\" %s]))
                   _ %s
                   process (.start pb)
                   completed (.waitFor process %s java.util.concurrent.TimeUnit/MILLISECONDS)
                   nrepl-limit %s
                   max-stderr-length (quot nrepl-limit 2)
                   truncate-with-limit
                   (fn [s limit]
                     (if (> (count s) limit)
                       (str (subs s 0 (- limit 16))
                            \"\\n... (truncated)\")
                       s))
                   read-it (fn [limit input-stream]
                              (-> (try
                                    (with-open [reader (clojure.java.io/reader input-stream)]
                                      (slurp reader))
                                    (catch Exception _ \"\"))
                                  (truncate-with-limit limit)))
                   stderr (read-it max-stderr-length (.getErrorStream process))
                   remaining-space (max 500 (- nrepl-limit 500 (count stderr)))
                   stdout (read-it remaining-space (.getInputStream process))]
               (if completed
                 {:exit-code (.exitValue process)
                  :stdout stdout
                  :stderr stderr
                  :timed-out false}
                 (do
                   (.destroyForcibly process)
                   {:exit-code -1
                    :stdout stdout
                    :stderr stderr
                    :timed-out true})))
             (catch Exception e
               {:exit-code -1
                :stdout \"\"
                :stderr (.getMessage e)
                :timed-out false
                :error (.getMessage e)}))"
          (pr-str command)
          ;; Fix working directory handling - only set if not nil
          (if working-directory
            (format "(when %s (.directory pb (java.io.File. %s)))"
                    (pr-str working-directory) (pr-str working-directory))
            "nil  ;; no working directory specified")
          timeout-ms
          ;; should be less than overall nrepl limit
          (int (* nrepl/truncation-length 0.85))))

(defn internal-error-result
  ([ex inner-value]
   (internal-error-result ex inner-value {}))
  ([ex inner-value opts]
   (into {:stdout ""
          :stderr (format
                   "Bash Tool execution failed.
IMPORTANT this was an error internal to the bash tool and and most likely has nothing to do with the bash command you submited.
EDN parsing failed: %s\nRaw result: %s"
                   (.getMessage ex)
                   inner-value)
          :timed-out false
          :exit-code -1
          :error "edn-parse-failure"}
         opts)))

(defn execute-bash-command-nrepl
  [nrepl-client-atom {:keys [command working-directory timeout-ms session] :as _args}]
  (log/debug "Using nREPL bash command: " command)
  ;; timeout-ms is now required - should be provided by tool
  (assert timeout-ms "timeout-ms is required")
  (when-not (command-allowed? command)
    (throw (ex-info "Command not allowed due to security restrictions"
                    {:command command
                     :error-details "The command contains restricted operations"})))
  (let [clj-shell-code (generate-shell-eval-code
                        command
                        working-directory
                        timeout-ms)
        eval-timeout-ms (+ 5000 timeout-ms)
        result (eval-core/evaluate-code
                @nrepl-client-atom
                (cond-> {:code clj-shell-code
                         :timeout-ms eval-timeout-ms}
                  session (assoc :session session)))
        output-map (into {} (:outputs result))
        inner-value (:value output-map)]
    (when (:error result)
      (log/warn "REPL evaluation failed for bash command"
                {:command command
                 :nrelp-eval-output-map output-map
                 :working-directory working-directory
                 :timeout-ms timeout-ms
                 :eval-timeout-ms eval-timeout-ms
                 :error result}))
    (if (not (:error result))
      (try
        ;; this is expected to work
        (edn/read-string inner-value)
        (catch Exception e
          (log/error e "Failed to parse bash command result as EDN"
                     {:command command
                      :nrepl-eval-output-map output-map
                      :inner-value inner-value
                      :result result})
          (internal-error-result e inner-value)))
      (try
        ;; this is expected to fail
        (edn/read-string inner-value)
        (catch Exception e
          (log/error "Bash command evaluation failed completely"
                     {:command command
                      :nrepl-eval-output-map output-map
                      :inner-value inner-value
                      :result result})
          (internal-error-result
           e
           inner-value
           {:stderr (str "Bash command failed to execute. "
                         "This COULD be an error INTERNAL to the bash tool "
                         "and probably not due to the command submitted:\n"
                         "command: " command
                         inner-value)
            :error "bash-command-failed"}))))))

