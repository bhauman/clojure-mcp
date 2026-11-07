(ns clojure-mcp.daemon.registry
  "Per-project state for the daemon: one nrepl-client-atom, config and tool
   set per canonical project dir. Entries are built lazily on a project's
   first call and rebuilt when its REPL restarts (its .nrepl-port changes)."
  (:require [clojure.java.io :as io]
            [clojure-mcp.config :as config]
            [clojure-mcp.config.schema :as schema]
            [clojure-mcp.core :as core]
            [clojure-mcp.tools :as tools]
            [clojure-mcp.tools.nrepl-ports.core :as ports]
            [taoensso.timbre :as log])
  (:import (java.time Duration Instant)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.function Function)))

(defn new-registry [] (atom {}))

(defonce ^:private ^ConcurrentHashMap build-locks (ConcurrentHashMap.))

(def provider-keys
  "Environment variables that give the sub-agent tools a model of their own."
  ["ANTHROPIC_API_KEY" "GEMINI_API_KEY" "OPENAI_API_KEY"])

(defn- build-entry [dir port]
  ;; The entry is shared by every session, so [:env] refs are not checked
  ;; against whichever session built it; a model checks its own when a
  ;; session's call creates it.
  (let [client-atom (atom (binding [schema/*validate-env-vars* false]
                            (core/create-and-start-nrepl-connection
                             (cond-> {:project-dir dir} port (assoc :port port)))))]
    {:dir dir
     :nrepl-client-atom client-atom
     :tool-fns (into {} (map (juxt :name :tool-fn)) (tools/build-all-tools client-atom))
     :nrepl-port port
     :last-used (Instant/now)}))

(defn- cleanup-entry! [{:keys [nrepl-client-atom]}]
  (remove-watch nrepl-client-atom :clojure-mcp.tools.scratch-pad.tool/scratch-pad-persistence))

(defn- current?
  "A missing .nrepl-port (REPL stopped) keeps the entry; a different one means
   the REPL restarted on a new port."
  [entry port]
  (and entry (or (nil? port) (= port (:nrepl-port entry)))))

(defn get-or-create!
  "The entry for `project-dir`, building or rebuilding it when needed."
  [registry project-dir]
  (let [dir (.getCanonicalPath (io/file project-dir))
        touch! (fn [entry]
                 (swap! registry #(cond-> % (contains? % dir) (assoc-in [dir :last-used] (Instant/now))))
                 entry)]
    (if-let [entry (let [e (get @registry dir)] (when (current? e (ports/read-nrepl-port-file dir)) e))]
      (touch! entry)
      (let [lock (.computeIfAbsent build-locks dir (reify Function (apply [_ _] (Object.))))]
        (locking lock
          (let [entry (get @registry dir)
                port (ports/read-nrepl-port-file dir)]
            (if (current? entry port)
              (touch! entry)
              (let [fresh (build-entry dir port)]
                (when entry
                  (log/info "Rebuilding" dir "for nREPL port" port)
                  (cleanup-entry! entry))
                (swap! registry assoc dir fresh)
                fresh))))))))

(defn evict-stale!
  "Drops entries idle for `ttl-ms` or more, then all but the `max-entries`
   most recently used."
  [registry {:keys [ttl-ms max-entries]}]
  (let [now (Instant/now)
        idle-ms #(.toMillis (Duration/between ^Instant (:last-used %) now))
        [old new] (swap-vals! registry
                              (fn [m]
                                (let [live (into {} (remove #(>= (idle-ms (val %)) ttl-ms)) m)]
                                  (if (> (count live) max-entries)
                                    (into {} (take max-entries) (sort-by (comp :last-used val) #(compare %2 %1) live))
                                    live))))]
    (doseq [[dir entry] old
            :when (not (contains? new dir))]
      (cleanup-entry! entry))))

(defn tool-enabled?
  "Whether the project's config (:enable-tools / :disable-tools) and the
   calling session's ENABLE_TOOLS / DISABLE_TOOLS allow `tool-name`. The
   daemon's tool list is shared by every project, so this is checked per call."
  [entry tool-name]
  (config/tool-id-enabled? @(:nrepl-client-atom entry) (keyword tool-name)))
