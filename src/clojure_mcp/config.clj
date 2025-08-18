(ns clojure-mcp.config
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure-mcp.dialects :as dialects]
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

(defn- validate-config-value
  "Validate a single config value with consistent error handling"
  [key value validator error-msg]
  (when (some? value)
    (when-not (validator value)
      (log/error "Invalid" key "value:" value "-" error-msg)
      (throw
       (ex-info
        (str "Invalid Config: " key " value: " value " - " error-msg)
        {key value})))))

(defn- validate-parse-nrepl-port-dependency
  "Validate that parse-nrepl-port has required start-nrepl-cmd"
  [parse-nrepl-port start-nrepl-cmd]
  (when (and parse-nrepl-port (not start-nrepl-cmd))
    (log/error "parse-nrepl-port requires start-nrepl-cmd to be set")
    (throw
     (ex-info
      "Invalid Config: parse-nrepl-port requires start-nrepl-cmd to be set"
      {:parse-nrepl-port parse-nrepl-port
       :start-nrepl-cmd start-nrepl-cmd}))))

(defn working-dir [project-dir]
  (let [ud (-> (io/file (or project-dir (System/getProperty "user.dir")))
               (.getCanonicalPath))]
    (assert (and (.isAbsolute (io/file ud))
                 (.isDirectory (io/file ud))))
    ud))

(defn- validate-config!
  [{:keys [write-file-guard
           start-nrepl-cmd parse-nrepl-port read-nrepl-port-file]}]
      ;; Validate all config values using helper functions
  (validate-config-value :write-file-guard write-file-guard
                         #(contains? #{:full-read :partial-read false} %)
                         "must be one of (:full-read, :partial-read, false)")
  (validate-config-value :start-nrepl-cmd start-nrepl-cmd
                         string?
                         "must be a string command")
  (validate-config-value :parse-nrepl-port parse-nrepl-port
                         boolean?
                         "must be a boolean")
  (validate-config-value :read-nrepl-port-file read-nrepl-port-file
                         boolean?
                         "must be a boolean")
  (validate-parse-nrepl-port-dependency parse-nrepl-port start-nrepl-cmd))

(defn process-config
  "Processes raw config map by validating values and applying defaults.
   Takes raw config map and user directory, returns processed config."
  [{:keys [allowed-directories] :as config} user-dir]
  (validate-config! config)
  ;; Transform and return config
  (let [processed-config (cond-> config
                           user-dir (assoc :nrepl-user-dir user-dir)
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
                           (assoc :bash-over-nrepl (boolean (:bash-over-nrepl config)))
                           (some? (:nrepl-env-type config))
                           (assoc :nrepl-env-type (:nrepl-env-type config)))]
    (log/info "Processed config:" processed-config)
    processed-config))

(defn read-config-from-file
  "Reads configuration from .clojure-mcp/config.edn in the given directory.
   Reads the file directly from the filesystem and returns raw config map."
  [cli-config-file user-dir]
  (let [config-file (if cli-config-file
                      (io/file cli-config-file)
                      (io/file user-dir ".clojure-mcp" "config.edn"))
        config (if (.exists config-file)
                 (try
                   (log/info "Loading early config from:" config-file)
                   (edn/read-string (slurp config-file))
                   (catch Exception e
                     (log/warn e "Failed to read config file:" (.getPath config-file))
                     {}))
                 {})]
    (log/info "Config file:" (.getPath config-file) "exists:" (.exists config-file))
    (log/info "Raw config:" config)
    config))

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
    ;; Validate the value and default to :partial-read if invalid
    (cond
      ;; nil means not configured, use default
      (nil? value) :partial-read
      ;; Valid values (including false)
      (contains? #{:full-read :partial-read false} value) value
      ;; Invalid values
      :else (do
              (log/warn "Invalid write-file-guard value:" value "- using default :partial-read")
              :partial-read))))

(defn get-nrepl-env-type
  "Returns the nREPL environment type.
   Defaults to :clj if not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :nrepl-env-type)]
    (if (nil? value)
      :clj ; Default to :clj when not specified
      value)))

(defn get-bash-over-nrepl
  "Returns whether bash commands should be executed over nREPL.
   Defaults to true for compatibility."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :bash-over-nrepl)
        nrepl-env-type (get-nrepl-env-type nrepl-client-map)]
    ;; XXX hack so that bash still works in other environments
    (if (nil? value)
      ;; default to the capability
      (dialects/handle-bash-over-nrepl? nrepl-env-type)
      ;; respect configured value
      (boolean value))))

(defn clojure-env?
  "Returns true if the nREPL environment is a Clojure environment."
  [nrepl-client-map]
  (= :clj (get-nrepl-env-type nrepl-client-map)))

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

(defn get-models
  "Returns the models configuration map.
   Defaults to empty map when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :models)]
    (if (nil? value)
      {} ; Default to empty map
      value)))

(defn get-tools-config
  "Returns the tools configuration map.
   Defaults to empty map when not specified."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :tools-config)]
    (if (nil? value)
      {} ; Default to empty map
      value)))

(defn get-tool-config
  "Returns configuration for a specific tool.
   Tool ID can be a keyword or string.
   Returns nil if no configuration exists for the tool."
  [nrepl-client-map tool-id]
  (let [tools-config (get-tools-config nrepl-client-map)
        ;; Normalize tool-id to keyword
        tool-key (if (string? tool-id) (keyword tool-id) tool-id)]
    (get tools-config tool-key)))

(defn get-mcp-client-hint [nrepl-client-map]
  (get-config nrepl-client-map :mcp-client))

(defn get-dispatch-agent-context
  "Returns dispatch agent context configuration.
   Can be:
   - true/false (boolean) - whether to use default code index
   - list of file paths - specific files to load into context
   Defaults to true for backward compatibility."
  [nrepl-client-map]
  (let [value (get-config nrepl-client-map :dispatch-agent-context)
        user-dir (get-nrepl-user-dir nrepl-client-map)]
    (cond
      (nil? value)
      true ;; Default to true to maintain existing behavior

      (boolean? value)
      value

      (sequential? value)
      ;; Process file paths
      (->> value
           (map (fn [path]
                  (let [file (io/file path)]
                    (if (.isAbsolute file)
                      (.getCanonicalPath file)
                      (.getCanonicalPath (io/file user-dir path))))))
           (filter #(.exists (io/file %)))
           vec)

      :else
      (do
        (log/warn "Invalid :dispatch-agent-context value, defaulting to true")
        true))))

(defn get-enable-tools [nrepl-client-map]
  (get-config nrepl-client-map :enable-tools))

(defn get-disable-tools [nrepl-client-map]
  (get-config nrepl-client-map :disable-tools))

(defn tool-id-enabled?
  "Check if a tool should be enabled based on :enable-tools and :disable-tools config.
   
   Logic:
   - If :enable-tools is nil, all tools are enabled (unless in :disable-tools)
   - If :enable-tools is [], no tools are enabled
   - If :enable-tools is provided, only those tools are enabled
   - :disable-tools is then applied to remove tools from the enabled set
   
   Both config lists can contain strings or keywords - they are normalized to keywords."
  [nrepl-client-map tool-id]
  (let [enable-tools (get-enable-tools nrepl-client-map)
        disable-tools (get-disable-tools nrepl-client-map)
        ;; Normalize tool-id to keyword
        tool-id (if (string? tool-id) (keyword tool-id) tool-id)
        enable-set (when enable-tools (set (map keyword enable-tools)))
        disable-set (when disable-tools (set (map keyword disable-tools)))]
    (cond
      ;; If enable is empty list [], nothing is enabled
      (and (some? enable-tools) (empty? enable-tools)) false

      ;; If enable is nil, all are enabled (unless in disable list)
      (nil? enable-tools) (not (contains? disable-set tool-id))

      ;; If enable is provided, check if tool is in enable list AND not in disable list
      :else (and (contains? enable-set tool-id)
                 (not (contains? disable-set tool-id))))))

(defn get-enable-prompts [nrepl-client-map]
  (get-config nrepl-client-map :enable-prompts))

(defn get-disable-prompts [nrepl-client-map]
  (get-config nrepl-client-map :disable-prompts))

(defn prompt-name-enabled?
  "Check if a prompt should be enabled based on :enable-prompts and :disable-prompts config.
   
   Logic:
   - If :enable-prompts is nil, all prompts are enabled (unless in :disable-prompts)
   - If :enable-prompts is [], no prompts are enabled
   - If :enable-prompts is provided, only those prompts are enabled
   - :disable-prompts is then applied to remove prompts from the enabled set
   
   Prompt names are converted to keywords for comparison.
   Both config lists can contain strings or keywords."
  [nrepl-client-map prompt-name]
  (let [enable-prompts (get-enable-prompts nrepl-client-map)
        disable-prompts (get-disable-prompts nrepl-client-map)
        enable-set (when enable-prompts (set enable-prompts))
        disable-set (when disable-prompts (set disable-prompts))]
    (cond
      ;; If enable is empty list [], nothing is enabled
      (and (some? enable-prompts) (empty? enable-prompts)) false

      ;; If enable is nil, all are enabled (unless in disable list)
      (nil? enable-prompts) (not (contains? disable-set prompt-name))

      ;; If enable is provided, check if prompt is in enable list AND not in disable list
      :else (and (contains? enable-set prompt-name)
                 (not (contains? disable-set prompt-name))))))

(defn get-enable-resources [nrepl-client-map]
  (get-config nrepl-client-map :enable-resources))

(defn get-disable-resources [nrepl-client-map]
  (get-config nrepl-client-map :disable-resources))

(defn resource-name-enabled?
  "Check if a resource should be enabled based on :enable-resources and :disable-resources config.
   
   Logic:
   - If :enable-resources is nil, all resources are enabled (unless in :disable-resources)
   - If :enable-resources is [], no resources are enabled
   - If :enable-resources is provided, only those resources are enabled
   - :disable-resources is then applied to remove resources from the enabled set
   
   Resource names are used as strings for comparison.
   Both config lists should contain strings."
  [nrepl-client-map resource-name]
  (let [enable-resources (get-enable-resources nrepl-client-map)
        disable-resources (get-disable-resources nrepl-client-map)
        enable-set (when enable-resources (set enable-resources))
        disable-set (when disable-resources (set disable-resources))]
    (cond
      ;; If enable is empty list [], nothing is enabled
      (and (some? enable-resources) (empty? enable-resources)) false

      ;; If enable is nil, all are enabled (unless in disable list)
      (nil? enable-resources) (not (contains? disable-set resource-name))

      ;; If enable is provided, check if resource is in enable list AND not in disable list
      :else (and (contains? enable-set resource-name)
                 (not (contains? disable-set resource-name))))))

(defn set-config*
  "Sets a config value in a map. Returns the updated map.
   This is the core function that set-config! uses."
  [nrepl-client-map k v]
  (assoc-in nrepl-client-map [::config k] v))

(defn set-config!
  "Sets a config value in an atom containing an nrepl-client map.
   Uses set-config* to perform the actual update."
  [nrepl-client-atom k v]
  (swap! nrepl-client-atom set-config* k v))
