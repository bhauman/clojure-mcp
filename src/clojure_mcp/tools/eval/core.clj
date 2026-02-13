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

(defn format-divider
  "Creates a divider string with namespace and env-type info.
   Format: *======== user | clj ========*"
  [ns-str env-type]
  (str "*======== " (or ns-str "?") " | " (if env-type (name env-type) "?") " ========*"))

(defn shadow-cljs-mode-message
  "Returns a message about the shadow-cljs REPL mode status."
  [in-cljs-mode?]
  (if in-cljs-mode?
    ";; shadow-cljs repl is in CLJS mode"
    (str ";; shadow-cljs repl is NOT in CLJS mode\n"
         ";; use (shadow/active-builds) to list builds available\n"
         ";; use (shadow/repl <build-id>) to jack into a CLJS repl")))

(defn partition-outputs
  "Partitions outputs into groups, each ending with a :ns entry.
   This groups all output leading up to and including the namespace divider."
  [outputs]
  (when (not-empty outputs)
    (let [[pre-ns-parts [ns-entry & xs]] (split-with #(not= :ns (first %)) outputs)]
      (cons (cond-> (vec pre-ns-parts)
              ns-entry (conj ns-entry))
            (partition-outputs xs)))))

(defn format-value
  "Formats a single output entry.
   - :value entries show the result with => prefix
   - :ns entries show the divider with namespace and env-type
   - :out/:err entries show raw text"
  [[k v] env-type]
  (string/trim-newline
   (case k
     :value (str "=> " v
                 (when (<= nrepl/truncation-length (count v))
                   " ... RESULT TRUNCATED"))
     :ns (format-divider v env-type)
     (str v))))

(defn format-eval-outputs
  "Formats a group of outputs (leading up to and including a value)."
  [outputs env-type]
  (->> outputs
       (map #(format-value % env-type))
       (string/join "\n")))

(defn partition-and-format-outputs
  "Formats all outputs, grouping by expression (each ending with :ns divider).
   Context map can include:
   - :env-type - the environment type keyword (:clj, :shadow, etc.)
   - :shadow-cljs-mode? - boolean for shadow-cljs CLJS mode status

   For shadow-cljs, prepends a mode status message."
  ([outputs] (partition-and-format-outputs outputs nil))
  ([outputs {:keys [env-type shadow-cljs-mode?] :as _context}]
   (let [prefix (when (= env-type :shadow)
                  [(shadow-cljs-mode-message shadow-cljs-mode?)])
         formatted (mapv #(format-eval-outputs % env-type) (partition-outputs outputs))]
     (if prefix
       (into prefix formatted)
       formatted))))

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
      (when (:value msg)
        (swap! outputs conj [:value (:value msg)])
        (swap! outputs conj [:ns (:ns msg)]))
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
