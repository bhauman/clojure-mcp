(ns clojure-mcp.config
  (:require
   [clojure.java.io :as io]
   [clojure-mcp.nrepl :as nrepl]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]))

(defn- relative-to [dir path]
  (try
    (let [f (io/file path)]
      (if (.isAbsolute f)
        (.getCanonicalPath f)
        (.getCanonicalPath (io/file dir path))))
    (catch Exception e
      (log/warn "Bad file paths " (pr-str [dir path]))
      nil)))

(defn process-remote-config [{:keys [allowed-directories emacs-notify write-file-guard cljfmt bash-over-nrepl] :as config} user-dir]
  (let [ud (io/file user-dir)]
    (assert (and (.isAbsolute ud) (.isDirectory ud)))
    (when (some? write-file-guard)
      (when-not (contains? #{:full-read :partial-read false} write-file-guard)
        (log/warn "Invalid write-file-guard value:" write-file-guard
                  "- using default :full-read")
        (throw (ex-info (str "Invalid Config: write-file-guard value:  " write-file-guard
                             "- must be one of (:full-read, :partial-read, false)")
                        {:write-file-guard write-file-guard}))))
    (cond-> config
      user-dir (assoc :nrepl-user-dir (.getCanonicalPath ud))
      true
      (assoc :allowed-directories
             (->> (cons user-dir allowed-directories)
                  (keep #(relative-to user-dir %))
                  distinct
                  vec))
      (some? (:emacs-notify config))
      (assoc :emacs-notify (boolean (:emacs-notify config)))
      (some? (:cljfmt config))
      (assoc :cljfmt (boolean (:cljfmt config)))
      (some? (:bash-over-nrepl config))
      (assoc :bash-over-nrepl (boolean (:bash-over-nrepl config))))))

(defn load-remote-config [nrepl-client user-dir]
  (let [remote-cfg-str
        (nrepl/tool-eval-code
         nrepl-client
         (pr-str
          '(do
             (require '[clojure.java.io :as io])
             (if-let [f (clojure.java.io/file "." ".clojure-mcp" "config.edn")]
               (when (.exists f) (clojure.edn/read-string (slurp f)))))))
        remote-config (try (edn/read-string remote-cfg-str)
                           (catch Exception _ {}))
        processed-config (process-remote-config remote-config user-dir)]
    (log/info "Loaded remote-config:" remote-config)
    (log/info "Processed config:" processed-config)
    processed-config))

(defn get-config [nrepl-client-map k]
  (get-in nrepl-client-map [::config k]))

(defn get-allowed-directories [nrepl-client-map]
  (get-config nrepl-client-map :allowed-directories))

(defn get-emacs-notify [nrepl-client-map]
  (get-config nrepl-client-map :emacs-notify))

(defn get-nrepl-user-dir [nrepl-client-map]
  (get-config nrepl-client-map :nrepl-user-dir))

(defn get-cljfmt [nrepl-client-map]
  (let [value (get-config nrepl-client-map :cljfmt)]
    (if (nil? value)
      true ; Default to true when not specified
      value)))

(defn get-write-file-guard [nrepl-client-map]
  (let [value (get-config nrepl-client-map :write-file-guard)]
    ;; Validate the value and default to :full-read if invalid
    (cond
      ;; nil means not configured, use default
      (nil? value) :full-read
      ;; Valid values (including false)
      (contains? #{:full-read :partial-read false} value) value
      ;; Invalid values
      :else (do
              (log/warn "Invalid write-file-guard value:" value "- using default :full-read")
              :full-read))))

(defn get-bash-over-nrepl
  "Returns whether bash commands should be executed over nREPL.
   Defaults to true for compatibility."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :bash-over-nrepl)]
    (if (nil? value)
      true ; Default to true when not specified
      (boolean value))))

(defn write-guard?
  "Returns true if write-file-guard is enabled (not false).
   This means file timestamp checking is active."
  [nrepl-client-map]
  (not= false (get-write-file-guard nrepl-client-map)))

(defn get-scratch-pad-load
  "Returns whether scratch pad persistence is enabled.
   Defaults to false when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :scratch-pad-load)]
    (if (nil? value)
      false ; Default to false when not specified
      (boolean value))))

(defn get-scratch-pad-file
  "Returns the scratch pad filename.
   Defaults to 'scratch_pad.edn' when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :scratch-pad-file)]
    (if (nil? value)
      "scratch_pad.edn" ; Default filename
      value)))

(defn set-config! [nrepl-client-atom k v]
  (swap! nrepl-client-atom assoc-in [::config k] v))

