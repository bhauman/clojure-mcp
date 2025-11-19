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
  (let [{:keys [code timeout_ms session]} opts
        timeout-ms (or timeout_ms 20000)
        outputs (atom [])
        error-occurred (atom false)
        form-str code
        add-output! (fn [prefix value] (swap! outputs conj [prefix value]))
        result-promise (promise)]

    ;; Evaluate the code
    ;; Push to eval history if available
    (when-let [state (::nrepl/state nrepl-client)]
      (swap! state update :clojure-mcp.repl-tools/eval-history conj form-str)

    ;; Evaluate the code, using the namespace parameter if provided
      (try
        (nrepl/eval-code-msg
         nrepl-client form-str
         (if session {:session session} {})
         (->> identity
              (nrepl/out-err
               #(add-output! :out %)
               #(add-output! :err %))
              (nrepl/value #(add-output! :value %))
              (nrepl/done (fn [_]
                            (deliver result-promise
                                     {:outputs @outputs
                                      :error @error-occurred})))
              (nrepl/error (fn [{:keys [exception]}]
                             (reset! error-occurred true)
                             (add-output! :err exception)
                             (deliver result-promise
                                      {:outputs @outputs
                                       :error true})))))
        (catch Exception e
            ;; prevent connection errors from confusing the LLM
          (log/error e "Error when trying to eval on the nrepl connection")
          (throw
           (ex-info
            (str "Internal Error: Unable to reach the nREPL "
                 "thus we are unable to execute the bash command.")
            {:error-type :connection-error}
            e))))

        ;; Wait for the result and return it
      (let [tmb (Object.)
            res (deref result-promise timeout-ms tmb)]
        (if-not (= tmb res)
          res
          (do
            (nrepl/interrupt nrepl-client)
            {:outputs [[:err (str "Eval timed out after " timeout-ms "ms.")]
                       [:err "Perhaps, you had an infinite loop or an eval that ran too long."]]
             :error true}))))))

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