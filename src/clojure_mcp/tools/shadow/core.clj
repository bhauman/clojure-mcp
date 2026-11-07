(ns clojure-mcp.tools.shadow.core
  "Forms and argument handling for the shadow-cljs tools.

   Each tool evaluates one self-contained Clojure form on the shadow-cljs
   nREPL. `cljs_eval` reaches a build's live runtime (browser or Node) through
   `shadow.cljs.devtools.api/cljs-eval`; the others read shadow's own worker
   and build-history state, which no runtime eval can see.

   When no build is given, the forms resolve it at eval time: the only active
   build, else an error listing the active builds."
  (:require
   [clojure.string :as string]
   [clojure-mcp.config :as config]
   [clojure-mcp.nrepl :as nrepl]))

(def ^:private build-name-re
  "Shape of a shadow-cljs build id once any leading colon is stripped.

   A whitelist on purpose: the build id is interpolated into an evaluated form
   with `pr-str`, and Clojure's printer does NOT escape a keyword's name --
   `(pr-str (keyword \"a) (do :b\"))` prints as `:a) (do :b`. So the valid
   shape is named: the characters Clojure accepts in a simple keyword, minus
   `/`, since a namespaced build id is not a thing shadow-cljs has."
  #"[A-Za-z0-9*+!\-_'?<>=.]+")

(defn- build-arg->name
  "The build id in `build` as a plain string with leading colons stripped, or
   nil when absent, blank or colons-only. Does not validate."
  [build]
  (let [s (if (keyword? build) (name build) (some-> build str))]
    (when-not (or (nil? s) (string/blank? s))
      (not-empty (string/replace s #"^:+" "")))))

(defn invalid-build-arg
  "Error message when `build` is present but not shaped like a shadow-cljs
   build id, else nil. Call before interpolating a build into any form."
  [build]
  (when-let [n (build-arg->name build)]
    (when-not (re-matches build-name-re n)
      (str "Invalid `build`: " (pr-str build)
           ". A shadow-cljs build id looks like \"app\" or \":app\": letters, digits and "
           "* + ! - _ ' ? < > = . only. Call cljs_project_state to list the live builds."))))

(defn normalize-build-kw
  "Build id as a keyword from \"app\", \":app\" or :app; nil when absent.
   Assumes `invalid-build-arg` already accepted it."
  [build]
  (some-> (build-arg->name build) keyword))

(defn resolve-port
  "The shadow-cljs nREPL port: `port-arg`, else the project's
   .shadow-cljs/nrepl.port. Throws when neither resolves."
  [nrepl-client-map port-arg]
  (or port-arg
      (nrepl/read-shadow-port-file (config/get-nrepl-user-dir nrepl-client-map))
      (throw (ex-info (str "No shadow-cljs nREPL port: pass `port`, or start shadow-cljs in this "
                           "project so it writes .shadow-cljs/nrepl.port. Find running ports with "
                           "list_nrepl_ports (env-type shadow).")
                      {:project-dir (config/get-nrepl-user-dir nrepl-client-map)}))))

(defn- with-active-build
  "Form string that binds `bid` to `build-kw`, or to the only active build,
   then evaluates `body`. Throws a readable error when no build resolves or
   the build is not active."
  [build-kw body]
  (str "(do (require 'shadow.cljs.devtools.api)"
       "  (let [active (vec (sort (shadow.cljs.devtools.api/active-builds)))"
       "        bid (or " (pr-str build-kw) " (when (= 1 (count active)) (first active)))]"
       "    (cond"
       "      (nil? bid)"
       "      (throw (ex-info (str \"`build` is required: active builds are \" active"
       "                           \". Pass build, or set :cljs-build in .clojure-mcp/config.edn.\") {}))"
       "      (not (some #{bid} active))"
       "      (throw (ex-info (str bid \" is not an active build; active builds are \" active) {}))"
       "      :else " body ")))"))

(defn cljs-eval-form
  "Form: evaluate `code` in the build's live runtime via the one-shot,
   non-nested cljs-eval API. Returns {:results [..] :out :err :ns}."
  [build-kw code runtime-id]
  (with-active-build
    build-kw
    (str "(shadow.cljs.devtools.api/cljs-eval bid " (pr-str code) " "
         (pr-str (cond-> {} runtime-id (assoc :runtime-id runtime-id))) ")")))

(defn project-state-form
  "Form: active builds with their autobuild flag and connected runtimes, plus
   the inactive build ids."
  []
  (str "(do (require 'shadow.cljs.devtools.api)"
       "  (let [active (into #{} (shadow.cljs.devtools.api/active-builds))"
       "        fmt (fn [bid]"
       "              (let [st (some-> bid shadow.cljs.devtools.api/get-worker :state-ref deref)]"
       "                {:build-id bid"
       "                 :auto-build-on-file-change (:autobuild st)"
       "                 :runtimes (->> st :runtimes vals"
       "                                (mapv (fn [r]"
       "                                        {:runtime-id (:client-id r)"
       "                                         :build-id (:build-id r)"
       "                                         :host-type (:host r)"
       "                                         :alive-since (:since r)"
       "                                         :user-agent (:user-agent r)"
       "                                         :dom (:dom r)"
       "                                         :connection-info (:connection-info r)})))}))]"
       "    {:builds (into {} (map (juxt identity fmt)) active)"
       "     :active-builds active"
       "     :inactive-builds (into #{} (remove (fn [b] (or (active b) (= :npm b))))"
       "                            (shadow.cljs.devtools.api/get-build-ids))}))"))

(defn build-status-form
  "Form: last compile status for `build-kw`, or for every build when nil."
  [build-kw]
  (str "(do (require 'shadow.cljs.devtools.server.runtime)"
       "  (let [bh (:build-history @shadow.cljs.devtools.server.runtime/instance-ref)]"
       "    (when-not bh (throw (ex-info \"shadow-cljs server not running\" {})))"
       "    (let [all @(:state-ref bh)"
       "          fmt-warn (fn [w]"
       "                     (cond-> {:message (:msg w)}"
       "                       (:resource-name w) (assoc :file (:resource-name w))"
       "                       (:line w) (assoc :line (:line w))"
       "                       (:column w) (assoc :column (:column w))"
       "                       (:warning w) (assoc :type (name (:warning w)))))"
       "          fmt (fn [st]"
       "                (cond-> {:status (:status st)}"
       "                  (:duration st) (assoc :duration-seconds (:duration st))"
       "                  (:compiled st) (assoc :compiled (:compiled st))"
       "                  (:resources st) (assoc :total-resources (:resources st))"
       "                  (seq (:warnings st)) (assoc :warnings (mapv fmt-warn (:warnings st)))"
       "                  (:report st) (assoc :error-report (:report st))"
       "                  (seq (:log st)) (assoc :build-log (:log st))))"
       "          bid " (pr-str build-kw) "]"
       "      (if bid"
       "        (if-let [st (get all bid)]"
       "          (assoc (fmt st) :build-id bid)"
       "          (throw (ex-info (str \"no build status for \" bid \"; builds with a status: \" (vec (keys all))) {})))"
       "        (into {} (map (fn [e] [(key e) (fmt (val e))])) all)))))"))

(defn list-ns-form
  "Form: the build's analyzer namespaces, optionally filtered by regex `match`."
  [build-kw match]
  (with-active-build
    build-kw
    (str "(let [nss (->> bid shadow.cljs.devtools.api/get-worker :state-ref deref"
         "                :build-state :compiler-env :cljs.analyzer/namespaces keys (mapv name))"
         "      re " (pr-str match)
         "      nss (if re (filterv (fn [n] (re-find (re-pattern re) n)) nss) nss)]"
         "  {:build-id bid :count (count nss) :namespaces (vec (sort nss))})")))
