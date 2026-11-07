(ns clojure-mcp.tools.eval.discovery
  "What clojure_eval tells the agent when no nREPL port resolves.

   A bare \"no port\" error is a dead end. This lists the REPLs running on the
   machine, warns that a port owned by another directory belongs to a
   different project's JVM, and says how to start this project's REPL, so the
   agent can recover on its own.

   Every start hint is read out of the project rather than assumed from a
   filename: `clj -M:nrepl` or `bb nrepl` emitted because a deps.edn merely
   exists sends the agent into `Specified aliases are undeclared`. A project
   can name its own command with `:start-repl-hint`; when it does, that comes
   first and the shadow-cljs `npx` guess is left out, since such a project
   starts shadow its own way."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure-mcp.config :as config]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.nrepl-ports.core :as ports]
   [clojure-mcp.tools.nrepl-ports.tool]
   [taoensso.timbre :as log]))

(defn- read-edn-file
  "Parse `f` under `dir` as EDN, or nil when it is absent or unparseable.
   Unknown reader tags are tolerated by discarding the tag and keeping the
   value."
  [dir f]
  (try
    (let [file (io/file dir f)]
      (when (.exists file)
        (edn/read-string {:default (fn [_tag v] v)} (slurp file))))
    (catch Exception _ nil)))

(defn- key->name
  "A build-file key as a plain string. These keys come from a file a human
   edited, so ints and other junk survive as text rather than throwing."
  [k]
  (cond (keyword? k) (name k)
        (symbol? k) (name k)
        (string? k) k
        :else (str k)))

(defn- nrepl-named? [k]
  (string/includes? (string/lower-case (key->name k)) "nrepl"))

(defn- nrepl-named-keys
  "Keys of `m` whose name mentions nrepl, as strings, sorted."
  [m]
  (when (map? m)
    (->> (keys m)
         (keep #(when (nrepl-named? %) (key->name %)))
         sort
         vec)))

(defn- nrepl-alias-commands
  "`clj` invocations for the nREPL-ish aliases in `aliases`, each spelled for
   the entry point that alias declares: `-M` reads `:main-opts` and `-X` runs
   `:exec-fn`, so the wrong flag starts a plain REPL. An alias declaring
   neither (dependency-only) is skipped. Ordered by alias name."
  [aliases]
  (when (map? aliases)
    (->> aliases
         (keep (fn [[k v]]
                 (when (and (nrepl-named? k) (map? v))
                   (let [n (key->name k)]
                     (when-let [flag (cond (:main-opts v) "-M"
                                           (:exec-fn v) "-X")]
                       [n (str "clj " flag ":" n)])))))
         (sort-by first)
         (mapv second))))

(defn start-repl-hints
  "Commands that would start a REPL for `dir`: `configured-hints` first, then
   what the project declares on disk. All matches are listed; only the human
   knows which one this task wants. Never throws."
  [dir configured-hints]
  (try
    (let [script (try (let [f (io/file dir "repl.sh")]
                        (and (.exists f) (.canExecute f)))
                      (catch Exception _ false))
          bb-tasks (nrepl-named-keys (:tasks (read-edn-file dir "bb.edn")))
          deps-cmds (nrepl-alias-commands (:aliases (read-edn-file dir "deps.edn")))
          shadow-builds (when (empty? configured-hints)
                          (let [b (:builds (read-edn-file dir "shadow-cljs.edn"))]
                            (when (map? b) (keys b))))
          lein? (try (.exists (io/file dir "project.clj")) (catch Exception _ false))]
      (cond-> (vec configured-hints)
        script (conj "./repl.sh")
        (seq bb-tasks) (into (map #(str "bb " %)) bb-tasks)
        (seq deps-cmds) (into deps-cmds)
        (seq shadow-builds) (into (map #(str "npx shadow-cljs watch " (key->name %))) shadow-builds)
        lein? (conj "lein repl")))
    (catch Throwable t
      (log/warn t "start-repl-hints probe threw; returning configured hints only")
      (vec configured-hints))))

(defn- port-scan
  "The nREPL servers list_nrepl_ports sees from `dir`, or nil when the scan fails."
  [dir]
  (try
    (ports/discover-nrepl-ports dir)
    (catch Exception e
      (log/warn e "nREPL port scan failed")
      nil)))

(defn no-nrepl-help
  "Eval-style error result for a clojure_eval call with no resolvable port:
   the running REPLs, the other-project warning, and how to start this
   project's REPL. An empty scan is stated plainly: list_nrepl_ports' own
   empty answer suggests a bare nREPL command, which would start a REPL
   without the project's aliases."
  [nrepl-client-map project-dir]
  (let [found (port-scan project-dir)
        live (filter :valid found)
        scan (cond
               (nil? found) nil
               (empty? live) "No nREPL servers are running."
               :else (string/join "\n" (:result (tool-system/format-results {:tool-type :list-nrepl-ports} found))))
        hints (start-repl-hints project-dir (config/get-start-repl-hints nrepl-client-map))
        start (if (seq hints)
                (str "This project provides: " (string/join " | " hints)
                     ". Starting one of these for this project is the expected first step; "
                     "ask the user first only when the REPL you would attach to is shared or "
                     "long-running, or lives in a directory that is not this project's.")
                (str "No start command was found in this project's repl.sh, bb.edn, deps.edn, "
                     "shadow-cljs.edn or project.clj: check its README for how it starts its REPL."))
        guidance (str "No nREPL was auto-discovered for working dir: " project-dir
                      " (no .nrepl-port there, and no `port` arg given). "
                      (if (seq live)
                        (str "Pick a port from the running REPLs listed above and retry `clojure_eval` "
                             "with `port: <n>` (for ClojureScript, use `cljs_eval` against the shadow-cljs nREPL). "
                             "The listing groups by the directory that owns each REPL, and a port under "
                             "another directory belongs to a different project's JVM: evaluating there runs "
                             "against that project's code, silently. Before the first real eval on a port you did not read out of this "
                             "project's own port files, confirm it with (System/getProperty \"user.dir\") and "
                             "check that the answer is INSIDE this project."
                             "\n\nIf none of those belong to this project, no REPL is running for it yet: "
                             "start one and retry. ")
                        (str "No running REPL was found: start this project's REPL, then retry "
                             "(for ClojureScript, `cljs_eval` reads the shadow-cljs port file once it runs). "))
                      start)]
    {:outputs (cond-> []
                scan (conj [:err scan])
                true (conj [:err guidance]))
     :error true
     :context nil}))
