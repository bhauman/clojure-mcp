(ns clojure-mcp.tools.eval.core
  "Core implementation for the eval tool.
   This namespace contains the pure functionality without any MCP-specific code."
  (:require
   [clojure-mcp.nrepl :as nrepl]
   [clojure-mcp.delimiter :as delimiter]
   [clojure-mcp.sexp.paren-utils :as paren-utils]
   [clojure.string :as string]
   [taoensso.timbre :as log]))

;; Eval results formatting
;; The goal is to make it clear for the LLM to understand
(defn partition-outputs [outputs]
  (when (not-empty outputs)
    (let [[non-val-parts [val & xs]] (split-with #(not= :value (first %)) outputs)]
      (cons (cond-> (vec non-val-parts)
              val (conj val))
            (partition-outputs xs)))))

(defn format-value [[k v]]
  (string/trim-newline
   (if (= k :value)
     (str "=> " v (if (<= nrepl/truncation-length (count v))
                    " ... RESULT TRUNCATED"
                    ""))
     (str v))))

(defn format-eval-outputs [outputs]
  (->> outputs
       (map format-value)
       (string/join "\n")))

(defn partition-and-format-outputs [outputs]
  (interpose "*===============================================*"
             (mapv format-eval-outputs (partition-outputs outputs))))

(defn repair-code
  [code]
  (if (delimiter/delimiter-error? code)
    (or (paren-utils/parinfer-repair code) code)
    code))

(defn- process-responses [responses]
  (let [outputs (atom [])
        error-occurred (atom false)]
    (doseq [msg responses]
      (when (:out msg) (swap! outputs conj [:out (:out msg)]))
      (when (:err msg) (swap! outputs conj [:err (:err msg)]))
      (when (:value msg) (swap! outputs conj [:value (:value msg)]))
      (when (:ex msg)
        (reset! error-occurred true)
        (swap! outputs conj [:err (:ex msg)]))
      (when (some #{"error" "eval-error"} (:status msg))
        (reset! error-occurred true)))
    {:outputs @outputs :error @error-occurred}))

(defn evaluate-code
  "Evaluates Clojure code using the nREPL client.
   
   Parameters:
   - nrepl-client: The nREPL client to use for evaluation
   - opts: A map of options:
     - :code The Clojure code to evaluate as a string
     - :timeout_ms the timeout in milliseconds
   
   Returns:
   - A map with :outputs (raw outputs), :error (boolean flag)"
  [nrepl-client opts]
  (let [{:keys [code timeout_ms session-type]} opts
        timeout-ms (or timeout_ms 20000)
        form-str code
        session-type (or session-type :default)
        conn (nrepl/open-connection nrepl-client)]

    ;; Push to eval history if available
    (when-let [state (::nrepl/state nrepl-client)]
      (swap! state update :clojure-mcp.repl-tools/eval-history conj form-str))

    (try
      (let [session-id (nrepl/ensure-session nrepl-client conn session-type)
            eval-id (nrepl/new-eval-id)
            fut (future
                  (try
                    (let [{:keys [responses]} (nrepl/eval-code* nrepl-client conn form-str
                                                                {:session-type session-type
                                                                 :session-id session-id
                                                                 :eval-id eval-id})]
                      (process-responses responses))
                    (catch Exception e
                      (log/error e "Error during nREPL eval")
                      {:outputs [[:err (str "Internal Error: " (.getMessage e))]] :error true})
                    (finally
                      ;; The connection is closed after the eval thread completes.
                      (nrepl/close-connection conn))))
            res (deref fut timeout-ms :timeout)]
        (if (= res :timeout)
          (do
            (nrepl/interrupt* conn session-id eval-id)
            (future-cancel fut)
            {:outputs [[:err (str "Eval timed out after " timeout-ms "ms.")]
                       [:err "Perhaps, you had an infinite loop or an eval that ran too long."]]
             :error true})
          res))
      (catch Exception e
        ;; ensure the connection is closed if something failed before the future started
        (nrepl/close-connection conn)
        ;; prevent connection errors from confusing the LLM
        (log/error e "Error when trying to eval on the nrepl connection")
        (throw
         (ex-info
          (str "Internal Error: Unable to reach the nREPL "
               "thus we are unable to execute the bash command.")
          {:error-type :connection-error}
          e))))))

(defn evaluate-with-repair
  "Evaluates Clojure code with automatic repair of delimiter errors.
   First attempts to repair any delimiter errors in the code, 
   then evaluates the repaired code if successful.
   
   Parameters:
   - nrepl-client: The nREPL client to use for evaluation
   - opts: A map of options:
     - :code The Clojure code to evaluate as a string
     - :session Optional session
   Returns:
   - A map with :outputs (raw outputs), :error (boolean flag), :repaired (boolean flag)"
  [nrepl-client opts]
  (let [{:keys [code]} opts
        repaired-code (repair-code code)
        repaired? (not= repaired-code code)
        opts (assoc opts :code repaired-code)]
    (assoc (evaluate-code nrepl-client opts)
           :repaired repaired?)))
