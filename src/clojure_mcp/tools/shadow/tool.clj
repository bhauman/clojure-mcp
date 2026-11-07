(ns clojure-mcp.tools.shadow.tool
  "The shadow-cljs tools: cljs_eval, cljs_build_status, cljs_project_state
   and cljs_list_ns. Each evaluates one generated form (see
   `clojure-mcp.tools.shadow.core`) on the shadow-cljs nREPL, under the same
   deadline as clojure_eval."
  (:require
   [clojure.string :as string]
   [clojure-mcp.config :as config]
   [clojure-mcp.nrepl :as nrepl]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.eval.core :as eval-core]
   [clojure-mcp.tools.eval.deadline :as deadline]
   [clojure-mcp.tools.shadow.core :as core]))

(def ^:private default-timeout-ms 20000)

(doseq [t [::cljs-eval ::cljs-build-status ::cljs-project-state ::cljs-list-ns]]
  (derive t ::shadow-tool))

(def ^:private port-prop
  {:port {:type :integer
          :description "shadow-cljs nREPL port. Optional: defaults to this project's .shadow-cljs/nrepl.port. Find others with list_nrepl_ports (env-type shadow)."}})

(def ^:private build-prop
  {:build {:type :string
           :description "shadow-cljs build id, with or without the colon (\"app\" or \":app\"). Optional: defaults to :cljs-build in .clojure-mcp/config.edn, else the only active build."}})

(def ^:private timeout-prop
  {:timeout_ms {:type :integer
                :description "Optional eval timeout in milliseconds."}})

(def ^:private tools
  {::cljs-eval
   {:description (str "Evaluate ClojureScript in a shadow-cljs build's LIVE runtime (browser tab or Node process), "
                      "with no nested REPL. Wraps shadow.cljs.devtools.api/cljs-eval and evaluates it on the "
                      "shadow-cljs nREPL.\n\n"
                      "Use it to inspect or drive a running cljs app: deref app state "
                      "(e.g. code \"(pr-str (get-in @re-frame.db/app-db [:some :path]))\"), dispatch events, "
                      "test predicates, or check the runtime (code \"js/process.version\" on Node).\n\n"
                      "Returns {:results [..stringified..] :out :err :ns}.\n\n"
                      "## Introspecting cljs vars\n"
                      "`clojure.repl/doc` and `clojure.repl/source` do not see ClojureScript vars. Use the "
                      "cljs.repl equivalents through this tool:\n"
                      "- doc:    code \"(with-out-str (cljs.repl/doc my.ns/thing))\"\n"
                      "- source: code \"(with-out-str (cljs.repl/source my.ns/thing))\"\n"
                      "For a build's namespace list use `cljs_list_ns`; for compile warnings/errors use "
                      "`cljs_build_status`; for what builds and runtimes exist use `cljs_project_state`.")
    :annotations {:title "Evaluate ClojureScript" :destructive? true}
    :schema {:type :object
             :properties (merge {:code {:type :string
                                        :description "ClojureScript expression(s) to evaluate in the runtime."}
                                 :runtime_id {:type :integer
                                              :description "Optional target runtime id when several runtimes are connected (see cljs_project_state)."}}
                                port-prop build-prop timeout-prop)
             :required [:code]}
    :requires-code? true
    :form-fn (fn [build-kw {:keys [code runtime_id]}] (core/cljs-eval-form build-kw code runtime_id))}

   ::cljs-build-status
   {:description (str "Last compile result for a shadow-cljs build: status, duration, compiled/total resources, "
                      "warnings (file/line/column/message) and the error report when it failed.\n\n"
                      "This is how you see a cljs compile error. It does NOT appear in cljs_eval, which only "
                      "reaches an already-running build. Omit `build` to get every build with a status.")
    :annotations {:title "shadow-cljs Build Status" :read-only? true}
    :schema {:type :object
             :properties (merge port-prop
                                {:build {:type :string
                                         :description "shadow-cljs build id (\"app\" or \":app\"). Omit for every build with a status."}}
                                timeout-prop)}
    :form-fn (fn [build-kw _] (core/build-status-form build-kw))
    :config-build? false}

   ::cljs-project-state
   {:description (str "What shadow-cljs is running right now: every active build, its autobuild flag, "
                      "and the runtimes connected to it (runtime-id, host, user-agent, connection info), "
                      "plus the active and inactive build ids.\n\n"
                      "Use it to find a `build` and `runtime_id` for cljs_eval, or to check whether the runtime "
                      "you expect is actually connected. Reads the shadow server state, not the runtime.")
    :annotations {:title "shadow-cljs Project State" :read-only? true}
    :schema {:type :object
             :properties (merge port-prop timeout-prop)}
    :form-fn (fn [_ _] (core/project-state-form))}

   ::cljs-list-ns
   {:description (str "Namespaces known to a shadow-cljs build's compiler (its analyzer env), optionally "
                      "filtered by a regex via `match`.\n\n"
                      "Use it to find the real name of a cljs namespace before evaluating in it.")
    :annotations {:title "List ClojureScript Namespaces" :read-only? true}
    :schema {:type :object
             :properties (merge port-prop build-prop timeout-prop
                                {:match {:type :string
                                         :description "Optional regex; only namespaces matching it are returned."}})}
    :form-fn (fn [build-kw {:keys [match]}] (core/list-ns-form build-kw match))}})

(defn create-shadow-tool
  "Tool config for one shadow tool, `tool-type` a key of `tools`."
  [nrepl-client-atom tool-type]
  (merge (get tools tool-type)
         {:tool-type tool-type
          :nrepl-client-atom nrepl-client-atom
          :deadline-grace-ms deadline/default-grace-ms}))

(defmethod tool-system/tool-description ::shadow-tool [{:keys [description]}] description)

(defmethod tool-system/tool-schema ::shadow-tool [{:keys [schema]}] schema)

(defmethod tool-system/tool-annotations ::shadow-tool [{:keys [annotations]}] annotations)

(defmethod tool-system/validate-inputs ::shadow-tool
  [{:keys [nrepl-client-atom requires-code? config-build?]} {:keys [code build port timeout_ms match runtime_id] :as inputs}]
  (when (and requires-code? (or (not (string? code)) (string/blank? code)))
    (throw (ex-info "Missing required parameter: code (a ClojureScript expression string)." {:inputs inputs})))
  (when-let [msg (core/invalid-build-arg build)]
    (throw (ex-info msg {:inputs inputs})))
  ;; match and runtime_id are printed into the evaluated form, and nested
  ;; map keys arrive keywordized (printed raw), so only the declared type
  ;; may pass
  (when (and (some? match) (not (string? match)))
    (throw (ex-info (str "Error parameter must be a string: match " (pr-str match)) {:inputs inputs})))
  (when (and (some? runtime_id) (not (pos-int? runtime_id)))
    (throw (ex-info (str "Error parameter must be positive integer: runtime_id " (pr-str runtime_id)) {:inputs inputs})))
  (when (and port (not (pos-int? port)))
    (throw (ex-info (str "Error parameter must be positive integer: port " (pr-str port)) {:inputs inputs})))
  (when (and timeout_ms (not (number? timeout_ms)))
    (throw (ex-info (str "Error parameter must be number: timeout_ms " (pr-str timeout_ms)) {:inputs inputs})))
  (let [m @nrepl-client-atom]
    (assoc inputs
           :port (core/resolve-port m port)
           ;; build-status reads every build when none is named, so it
           ;; ignores the configured default
           :build-kw (or (core/normalize-build-kw build)
                         (when-not (false? config-build?) (config/get-cljs-build m))))))

(defmethod tool-system/execute-tool ::shadow-tool
  [{:keys [nrepl-client-atom form-fn deadline-grace-ms] :as tool-config} {:keys [port timeout_ms build-kw] :as inputs}]
  (let [timeout-ms (or timeout_ms default-timeout-ms)
        tool-name (tool-system/tool-name tool-config)]
    (deadline/call-with-deadline
     tool-name
     (deadline/deadline-ms timeout-ms deadline-grace-ms)
     (fn []
       (try
         (let [client (nrepl/with-port-initialized @nrepl-client-atom port)]
           (eval-core/evaluate-code client {:code (form-fn build-kw inputs) :timeout_ms timeout-ms}))
         (catch Exception e
           {:outputs [[:err (str "Could not reach the shadow-cljs nREPL on port " port ": "
                                 (or (ex-message (or (ex-cause e) e)) (str e))
                                 ". The port may be stale (shadow-cljs not running). "
                                 "Find live ports with list_nrepl_ports (env-type shadow).")]]
            :error true}))))))

(defmethod tool-system/format-results ::shadow-tool [_ {:keys [outputs error]}]
  {:result (eval-core/partition-and-format-outputs outputs {:env-type :shadow :show-shadow-message? false})
   :error error})

(defn shadow-tools
  "Registration maps for the four shadow-cljs tools."
  [nrepl-client-atom]
  (mapv #(tool-system/registration-map (create-shadow-tool nrepl-client-atom %))
        [::cljs-eval ::cljs-build-status ::cljs-project-state ::cljs-list-ns]))
