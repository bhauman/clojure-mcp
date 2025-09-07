(ns clojure-mcp.core
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.config :as config]
            [clojure-mcp.dialects :as dialects]
            [clojure-mcp.file-content :as file-content]
            [clojure-mcp.nrepl-launcher :as nrepl-launcher])
  (:import [io.modelcontextprotocol.server.transport
            StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer McpServerFeatures
            McpServerFeatures$AsyncToolSpecification
            McpServerFeatures$AsyncResourceSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities
            McpSchema$Tool
            McpSchema$CallToolResult
            McpSchema$TextContent
            McpSchema$ImageContent
            McpSchema$Prompt
            McpSchema$PromptArgument
            McpSchema$GetPromptRequest
            McpSchema$GetPromptResult
            McpSchema$PromptMessage
            McpSchema$Role
            McpSchema$LoggingLevel
            McpSchema$Resource
            McpSchema$ReadResourceResult
            McpSchema$TextResourceContents
            McpSchema$ResourceContents]
           [io.modelcontextprotocol.server McpServer McpServerFeatures
            McpServerFeatures$AsyncToolSpecification
            McpServerFeatures$AsyncPromptSpecification]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

(defn create-mono-from-callback
  "Creates a function that takes the exchange and the arguments map and
  returns a Mono promise The callback function should take three
  arguments: 
   - exchange: The MCP exchange object 
   - arguments: The arguments map sent in the request 
   - continuation: A function that will be called with the result and will fullfill the promise"
  [callback-fn]
  (fn [exchange arguments]
    (Mono/create
     (reify java.util.function.Consumer
       (accept [this sink]
         (callback-fn
          exchange
          arguments
          (fn [result]
            (.success sink result))))))))

(defn- adapt-result [result]
  (cond
    (string? result) (McpSchema$TextContent. result)
    (file-content/file-response? result)
    (file-content/file-response->file-content result)
    :else (McpSchema$TextContent. " ")))

(defn ^McpSchema$CallToolResult adapt-results [list-str error?]
  (McpSchema$CallToolResult. (vec (keep adapt-result list-str)) error?))

(defn create-async-tool
  "Creates an AsyncToolSpecification with the given parameters.
   
   Takes a map with the following keys:
    :name         - The name of the tool
    :description  - A description of what the tool does
    :schema       - JSON schema for the tool's input parameters
    :service-atom - The atom holding the nREPL client connection.
    :tool-fn      - Function that implements the tool's logic.
                    Signature: (fn [exchange args-map nrepl-client clj-result-k] ... )
                      * exchange     - ignored (or used for advanced features)
                      * arg-map      - map with string keys representing the mcp tool call args
                      * nrepl-client - the validated and dereferenced nREPL client
                      * clj-result-k - continuation fn taking vector of strings and boolean error flag."
  [{:keys [name description schema tool-fn]}]
  (let [schema-json (json/write-str schema)
        mono-fn (create-mono-from-callback
                 (fn [exchange arg-map mono-fill-k]
                   (let [clj-result-k
                         (fn [res-list error?]
                           (mono-fill-k (adapt-results res-list error?)))]
                     (tool-fn exchange arg-map clj-result-k))))]
    (McpServerFeatures$AsyncToolSpecification.
     (McpSchema$Tool. name description schema-json)
     (reify java.util.function.BiFunction
       (apply [this exchange arguments]
         (log/debug (str "Args from MCP: " (pr-str arguments)))
         (mono-fn exchange arguments))))))

(defn ^McpSchema$GetPromptResult adapt-prompt-result
  "Adapts a Clojure prompt result map into an McpSchema$GetPromptResult.
   Expects a map like {:description \"...\" :messages [{:role :user :content \"...\"}]}"
  [{:keys [description messages]}]
  (let [mcp-messages (mapv (fn [{:keys [role content]}]
                             (McpSchema$PromptMessage.
                              (case role ;; Convert keyword role to McpSchema$Role enum
                                ;; :system McpSchema$Role/SYSTEM
                                :user McpSchema$Role/USER
                                :assistant McpSchema$Role/ASSISTANT
                                ;; Add other roles if needed
                                )
                              (McpSchema$TextContent. content))) ;; Assuming TextContent for now
                           messages)]
    (McpSchema$GetPromptResult. description mcp-messages)))

(defn create-async-prompt
  "Creates an AsyncPromptSpecification with the given parameters.
   
   Takes a map with the following keys:
    :name        - The name (ID) of the prompt
    :description - A description of the prompt
    :arguments   - A vector of maps, each defining an argument:
                   {:name \"arg-name\" :description \"...\" :required? true/false}
    :prompt-fn   - Function that implements the prompt logic.
                   Signature: (fn [exchange request-args clj-result-k] ... )
                     * exchange - The MCP exchange object
                     * request-args - Map of arguments provided in the client request
                     * clj-result-k - Continuation fn taking one map argument:
                                      {:description \"...\" :messages [{:role :user :content \"...\"}]} "
  [{:keys [name description arguments prompt-fn]}]
  (let [mcp-args (mapv (fn [{:keys [name description required?]}]
                         (McpSchema$PromptArgument. name description required?))
                       arguments)
        mcp-prompt (McpSchema$Prompt. name description mcp-args)
        mono-fn (create-mono-from-callback ;; Reuse the existing helper
                 (fn [_ request mono-fill-k]
                   ;; The request object has an .arguments() method
                   (let [request-args (.arguments ^McpSchema$GetPromptRequest request)] ;; <-- Corrected method call
                     (prompt-fn _ request-args
                                (fn [clj-result-map]
                                  (mono-fill-k (adapt-prompt-result clj-result-map)))))))]
    (McpServerFeatures$AsyncPromptSpecification.
     mcp-prompt
     (reify java.util.function.BiFunction
       (apply [this exchange request]
         (mono-fn exchange request))))))

(defn add-tool
  "Helper function to create an async tool from a map and add it to the server."
  [mcp-server tool-map]
  (.removeTool mcp-server (:name tool-map))
  ;; Pass the service-atom along when creating the tool
  (-> (.addTool mcp-server (create-async-tool tool-map))
      (.subscribe)))

(defn create-async-resource
  "Creates an AsyncResourceSpecification with the given parameters.
   
   Takes a map with the following keys:
    :url          - The URL of the resource
    :name         - The name of the resource
    :description  - A description of what the resource is
    :mime-type    - The MIME type of the resource
    :resource-fn  - Function that implements the resource retrieval logic.
                    Signature: (fn [exchange request clj-result-k] ... )
                      * exchange     - The MCP exchange object
                      * request      - The request object
                      * clj-result-k - continuation fn taking a vector of strings"
  [{:keys [url name description mime-type resource-fn]}]
  (let [resource (McpSchema$Resource. url name description mime-type nil)
        mono-fn (create-mono-from-callback
                 (fn [exchange request mono-fill-k]
                   (resource-fn
                    exchange
                    request
                    (fn [result-strings]
                      ;; Create TextResourceContents objects with the URL and MIME type
                      (let [resource-contents (mapv #(McpSchema$TextResourceContents. url mime-type %)
                                                    result-strings)]
                        ;; Create ReadResourceResult with the list of TextResourceContents
                        (mono-fill-k (McpSchema$ReadResourceResult. resource-contents)))))))]
    (McpServerFeatures$AsyncResourceSpecification.
     resource
     (reify java.util.function.BiFunction
       (apply [this exchange request]
         (mono-fn exchange request))))))

(defn add-resource
  "Helper function to create an async resource from a map and add it to the server.
   
   Takes an MCP server and a resource map with:
    :url          - The URL of the resource
    :name         - The name of the resource
    :description  - A description of what the resource is
    :mime-type    - The MIME type of the resource
    :resource-fn  - Function that implements the resource retrieval logic."
  [mcp-server resource-map]
  (.removeResource mcp-server (:url resource-map))
  (-> (.addResource mcp-server (create-async-resource resource-map))
      (.subscribe)))

(defn add-prompt
  "Helper function to create an async prompt from a map and add it to the server.
   
   Takes an MCP server and a prompt map with:
    :name        - The name (ID) of the prompt
    :description - A description of the prompt
    :arguments   - A vector of maps, each defining an argument
    :prompt-fn   - Function that implements the prompt logic."
  [mcp-server prompt-map]
  (.removePrompt mcp-server (:name prompt-map))
  (-> (.addPrompt mcp-server (create-async-prompt prompt-map))
      (.subscribe)))

;; helper tool to demonstrate how all this gets hooked together

(defn mcp-server
  "Creates a basic stdio mcp server"
  []
  (log/info "Starting MCP server")
  (try
    (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
          server (-> (McpServer/async transport-provider)
                     (.serverInfo "clojure-server" "0.1.0")
                     (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                        (.tools true)
                                        (.prompts true)
                                        (.resources true true) ;; resources method takes two boolean parameters
                                        #_(.logging)
                                        (.build)))
                     (.build))]

      (log/info "MCP server initialized successfully")
      server)
    (catch Exception e
      (log/error e "Failed to initialize MCP server")
      (throw e))))

;; Setting up a server
;; this could be in a new namespace

(defn fetch-config [nrepl-client-map config-file cli-env-type env-type project-dir]
  (let [user-dir (dialects/fetch-project-directory nrepl-client-map env-type project-dir)]
    (when-not user-dir
      (log/warn "Could not determine working directory")
      (throw (ex-info "No project directory!!" {})))
    (log/info "Working directory set to:" user-dir)
    (let [config (config/load-config config-file user-dir)
          final-env-type (or cli-env-type
                             (if (contains? config :nrepl-env-type)
                               (:nrepl-env-type config)
                               env-type))]
      (assoc nrepl-client-map ::config/config (assoc config :nrepl-env-type final-env-type)))))

(defn create-and-start-nrepl-connection
  "Convenience higher-level API function to create and initialize an nREPL connection.
   
   This function handles the complete setup process including:
   - Creating the nREPL client connection
   - Starting the polling mechanism
   - Loading required namespaces and helpers (if Clojure environment)
   - Setting up the working directory
   - Loading configuration
   
   Takes initial-config map with :port and optional :host, :project-dir, :nrepl-env-type, :config-file.
   Returns the configured nrepl-client-map with ::config/config attached."
  [{:keys [project-dir config-file] :as initial-config}]
  (log/info "Creating nREPL connection with config:" initial-config)
  (try
    (let [nrepl-client-map (nrepl/create (dissoc initial-config :project-dir :nrepl-env-type))
          cli-env-type (:nrepl-env-type initial-config)
          _ (do
              (log/info "nREPL client map created")
              (nrepl/start-polling nrepl-client-map)
              (log/info "Started polling nREPL"))
          ;; Detect environment type early
          ;; TODO this needs to be sorted out
          env-type (dialects/detect-nrepl-env-type nrepl-client-map)
          nrepl-client-map-with-config (fetch-config nrepl-client-map
                                                     config-file
                                                     cli-env-type
                                                     env-type
                                                     project-dir)
          nrepl-env-type' (config/get-config nrepl-client-map-with-config :nrepl-env-type)]
      (log/debug "Initializing Clojure environment")
      (dialects/initialize-environment nrepl-client-map-with-config nrepl-env-type')
      (dialects/load-repl-helpers nrepl-client-map-with-config nrepl-env-type')
      (log/debug "Environment initialized")
      nrepl-client-map-with-config)
    (catch Exception e
      (log/error e "Failed to create nREPL connection")
      (throw e))))

(defn create-additional-connection
  ([nrepl-client-atom initial-config]
   (create-additional-connection nrepl-client-atom initial-config identity))
  ([nrepl-client-atom initial-config initialize-fn]
   (log/info "Creating additional nREPL connection" initial-config)
   (try
     (let [nrepl-client-map (nrepl/create initial-config)]
       (nrepl/start-polling nrepl-client-map)
       ;; copy config
       ;; maybe we should create this just like the normal nrelp connection?
       ;; we should introspect the project and get a working directory
       ;; and maybe add it to allowed directories for both
       (when initialize-fn (initialize-fn nrepl-client-map))
       (assert (::config/config @nrepl-client-atom))
       ;; copy config over for now
       (assoc nrepl-client-map ::config/config (::config/config @nrepl-client-atom)))
     (catch Exception e
       (log/error e "Failed to create additional nREPL connection")
       (throw e)))))

(defn close-servers
  "Convenience higher-level API function to gracefully shut down MCP and nREPL servers.
   
   This function handles the complete shutdown process including:
   - Stopping nREPL polling if a client exists in nrepl-client-atom
   - Gracefully closing the MCP server
   - Proper error handling and logging"
  [nrepl-client-atom]
  (log/info "Shutting down servers")
  (try
    (when-let [client @nrepl-client-atom]
      (log/info "Stopping nREPL polling")
      (nrepl/stop-polling client)
      ;; Clean up auto-started nREPL process if present
      (when-let [nrepl-process (:nrepl-process client)]
        (log/info "Cleaning up auto-started nREPL process")
        (nrepl-launcher/destroy-nrepl-process nrepl-process))
      (when-let [mcp-server (:mcp-server client)]
        (log/info "Closing MCP server gracefully")
        (.closeGracefully mcp-server)
        (log/info "Servers shut down successfully")))
    (catch Exception e
      (log/error e "Error during server shutdown")
      (throw e))))

(s/def ::port pos-int?)
(s/def ::host string?)
(s/def ::nrepl-env-type keyword?)
(s/def ::project-dir (s/and string?
                            #(try (let [f (io/file %)]
                                    (and (.exists f) (.isDirectory f)))
                                  (catch Exception _ false))))
(s/def ::config-file (s/and string?
                            #(try (let [f (io/file %)]
                                    (and (.exists f) (.isFile f)))
                                  (catch Exception _ false))))
(s/def ::start-nrepl-cmd (s/coll-of string? :kind vector?))
(s/def ::parse-nrepl-port boolean?)
(s/def ::nrepl-args (s/keys :req-un []
                            :opt-un [::port ::host ::config-file ::project-dir ::nrepl-env-type
                                    ::start-nrepl-cmd ::parse-nrepl-port]))

(def nrepl-client-atom (atom nil))

(defn coerce-options [{:keys [project-dir config-file] :as opts}]
  (cond-> opts
    (symbol? project-dir)
    (assoc :project-dir (str project-dir))
    (symbol? config-file)
    (assoc :config-file (str config-file))))

(defn validate-options
  "Validates the options map for build-and-start-mcp-server.
   Throws an exception with spec explanation if validation fails."
  [opts]
  (let [opts (coerce-options opts)]
    (if-not (s/valid? ::nrepl-args opts)
      (let [explanation (s/explain-str ::nrepl-args opts)]
        (println "Invalid options:" explanation)
        (log/error "Invalid options:" explanation)
        (throw (ex-info "Invalid options for MCP server"
                        {:explanation explanation
                         :spec-data (s/explain-data ::nrepl-args opts)})))
      opts)))

(defn build-and-start-mcp-server-impl
  "Internal implementation of MCP server startup.
   
   Builds and starts an MCP server with the provided configuration.
   
   This is the main entry point for creating custom MCP servers. It handles:
   - Validating input options
   - Creating and starting the nREPL connection
   - Setting up the working directory
   - Calling factory functions to create tools, prompts, and resources
   - Registering everything with the MCP server
   
   Args:
   - nrepl-args: Map with connection settings
     - :port (required) - nREPL server port
     - :host (optional) - nREPL server host (defaults to localhost)
     - :project-dir (optional) - Root directory for the project (must exist)
   
   - config: Map with factory functions
     - :make-tools-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of tools
     - :make-prompts-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of prompts  
     - :make-resources-fn - (fn [nrepl-client-atom working-dir] ...) returns seq of resources
   
   All factory functions are optional. If not provided, that category won't be populated.
   
   Side effects:
   - Stores the nREPL client in core/nrepl-client-atom
   - Starts the MCP server on stdio
   
   Returns: nil"
  [nrepl-args {:keys [make-tools-fn
                      make-prompts-fn
                      make-resources-fn]}]
  ;; the nrepl-args are a map with :port and optional :host
  ;; Note: validation should be done by caller
  (let [_ (assert (:port nrepl-args) "Port must be provided for build-and-start-mcp-server-impl")
        nrepl-client-map (create-and-start-nrepl-connection nrepl-args)
        working-dir (config/get-nrepl-user-dir nrepl-client-map)
        ;; Store nREPL process (if auto-started) in client map for cleanup
        nrepl-client-with-process (if-let [process (:nrepl-process nrepl-args)]
                                    (assoc nrepl-client-map :nrepl-process process)
                                    nrepl-client-map)
        _ (reset! nrepl-client-atom nrepl-client-with-process)
        resources (when make-resources-fn
                    (doall (make-resources-fn nrepl-client-atom working-dir)))
        tools (when make-tools-fn
                (doall (make-tools-fn nrepl-client-atom working-dir)))
        prompts (when make-prompts-fn
                  (doall (make-prompts-fn nrepl-client-atom working-dir)))
        mcp (mcp-server)]
    (doseq [tool tools]
      (when (config/tool-id-enabled? nrepl-client-map (:id tool))
        (log/debug "Enabling tool:" (:id tool))
        (add-tool mcp tool)))
    (doseq [resource resources]
      (when (config/resource-name-enabled? nrepl-client-map (:name resource))
        (log/debug "Enabling resource:" (:name resource))
        (add-resource mcp resource)))
    (doseq [prompt prompts]
      (when (config/prompt-name-enabled? nrepl-client-map (:name prompt))
        (log/debug "Enabling prompt:" (:name prompt))
        (add-prompt mcp prompt)))
    (swap! nrepl-client-atom assoc :mcp-server mcp)
    nil))

(defn build-and-start-mcp-server
  "Builds and starts an MCP server with optional automatic nREPL startup.
   
   This function wraps build-and-start-mcp-server-impl with nREPL auto-start capability.
   
   If auto-start conditions are met (see nrepl-launcher/should-start-nrepl?), it will:
   1. Start an nREPL server process using :start-nrepl-cmd
   2. Parse the port from process output (if :parse-nrepl-port is true)
   3. Pass the discovered port to the main MCP server setup
   
   Otherwise, it requires a :port parameter.
   
   Args:
   - nrepl-args: Map with connection settings and optional nREPL start
     configuration
     - :port (required if not auto-starting) - nREPL server port
     - :host (optional) - nREPL server host (defaults to localhost)  
     - :project-dir (optional) - Root directory for the project
     - :start-nrepl-cmd (optional) - Command to start nREPL server
     - :parse-nrepl-port (optional) - Parse port from command output (default true)
   
   - config: Map with factory functions (same as
     build-and-start-mcp-server-impl)
   
   Auto-start conditions (must satisfy ONE):
   1. Both :start-nrepl-cmd AND :project-dir provided in nrepl-args
   2. Current directory contains .clojure-mcp/config.edn with :start-nrepl-cmd
   
   Returns: nil"
  [nrepl-args config]
  (let [validated-args (validate-options nrepl-args)
        args-with-port (nrepl-launcher/maybe-start-nrepl-process
                        validated-args)]
    ;; Ensure we have a port after auto-start or validation
    (when-not (:port args-with-port)
      (throw
       (ex-info
        "No nREPL port available - either provide :port or configure auto-start"
        {:provided-args nrepl-args})))
    (build-and-start-mcp-server-impl args-with-port config)))
