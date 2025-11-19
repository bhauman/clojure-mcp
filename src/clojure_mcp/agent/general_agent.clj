(ns clojure-mcp.agent.general-agent
  "A generalized agent library that can be parameterized with system prompts,
   context, tools, memory, and models."
  (:require [clojure.string :as string]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [clojure-mcp.agent.langchain :as chain]
            [clojure-mcp.tools.project.core :as project-core]
            [clojure-mcp.tools :as tools]
            [clojure-mcp.utils.file :as file-utils])
  (:import
   [clojure_mcp.agent.langchain AiService]
   [dev.langchain4j.data.message UserMessage TextContent]))

(def DEFAULT-MEMORY-SIZE 100)
(def DEFAULT-STATELESS-BUFFER 100)
(def MIN-PERSISTENT-WINDOW 10)

(defn build-read-only-tools
  "Builds the read-only tools for agents.
   These tools are safe for exploration and analysis without modifying files.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   
   Returns: A vector of read-only tools"
  [nrepl-client-atom]
  (tools/build-read-only-tools nrepl-client-atom))

(defn build-context-strings
  "Build context strings based on the context configuration.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   - working-directory: The working directory path
   - context-config: Can be:
     - true: use default code index and project summary
     - sequential: use specific file paths
     - false/nil: no context
   
   Returns: A vector of context strings"
  [nrepl-client-atom working-directory context-config]
  (cond
    (true? context-config)
    (let [code-index-file (io/file working-directory ".clojure-mcp" "code_index.txt")
          proj-summary-file (io/file working-directory "PROJECT_SUMMARY.md")
          {:keys [outputs error]} (when nrepl-client-atom
                                    (project-core/inspect-project nrepl-client-atom))
          context-strings (cond-> []
                            (.exists proj-summary-file)
                            (conj (str "This is a project summary:\n"
                                       (file-utils/slurp-utf8 proj-summary-file)))

                            (and (not error) outputs)
                            (conj (str "This is the current project structure:\n"
                                       (string/join "\n" outputs)))

                            (.exists code-index-file)
                            (conj (str "This is a code index of the code in this project.
Please use it to inform you as to which files should be investigated.\n=======================\n"
                                       (file-utils/slurp-utf8 code-index-file))))]
      context-strings)

    (sequential? context-config)
    (vec (for [file-path context-config
               :let [file (io/file file-path)]
               :when (.exists file)]
           (str "File: " file-path "\n"
                "=======================\n"
                (file-utils/slurp-utf8 file) "\n\n")))

    :else
    []))

(defn prep-agent-system-atom
  "Prepares an nrepl-client-atom for agent use by:
   - Removing :mcp-client-hint to isolate from main conversation
   - Creating a fresh ::file-timestamps atom for isolated file tracking
   
   Args:
   - nrepl-client-atom: The original nREPL client atom
   
   Returns: A new atom with isolated configuration for agent use"
  [nrepl-client-atom]
  (let [original-state @nrepl-client-atom
        ;; Remove MCP client hint and file timestamps
        prepped-state (-> original-state
                          (dissoc :mcp-client-hint)
                          (dissoc :clojure-mcp.tools.unified-read-file.file-timestamps/file-timestamps))]
    (atom prepped-state)))

(defn create-memory-for-config
  "Creates memory based on configuration.
   nil/false/< 10 = stateless with 100 message buffer
   number >= 10 = persistent sliding window of that size"
  [memory-size]
  (cond
    ;; Stateless mode (default)
    (or (nil? memory-size)
        (false? memory-size))
    {:memory (chain/chat-memory DEFAULT-STATELESS-BUFFER)
     :stateless? true}

    ;; Number less than 10 - treat as stateless
    (and (number? memory-size)
         (< memory-size MIN-PERSISTENT-WINDOW))
    {:memory (chain/chat-memory DEFAULT-STATELESS-BUFFER)
     :stateless? true}

    ;; Persistent sliding window mode (10 or greater)
    (number? memory-size)
    {:memory (chain/chat-memory memory-size)
     :stateless? false}

    :else
    (throw (ex-info "Invalid memory-size value"
                    {:value memory-size}))))

(defn initialize-memory-with-context!
  "Initialize memory with the provided context strings.
   
   Args:
   - memory: The langchain memory object
   - context: A list of strings to add as context
   
   Returns: The memory object with context added"
  [memory context]
  (when (seq context)
    (.add memory
          (UserMessage.
           (vec (for [content context]
                  (TextContent/from content))))))
  memory)

(defn reset-memory-if-needed!
  "Reset memory if it exceeds the specified size limit, re-initializing with context.
   
   Args:
   - memory: The langchain memory object
   - context: The context strings to re-initialize with
   - memory-size: The maximum memory size before reset
   
   Returns: The memory object (possibly reset)"
  [memory context memory-size]
  (if (> (count (.messages memory)) (- memory-size 15))
    (let [cleared-memory (doto memory (.clear))]
      (initialize-memory-with-context! cleared-memory context))
    memory))

(defn create-general-agent
  "Creates a general AI agent service with the specified configuration.
   
   Args:
   - config: A map containing:
     :system-prompt - The system prompt string for the agent
     :context - A list of strings providing context (optional)
     :tools - A vector of tools the agent can use
     :memory - A langchain memory object (optional, creates one if not provided)
     :model - A langchain model object
     :memory-size - Memory configuration (nil/false/<10 = stateless, >=10 = persistent window)
   
   Returns: A map containing:
     :service - The built AI service
     :memory - The memory object
     :model - The model object
     :tools - The tools vector
     :system-message - The system prompt
     :stateless? - Whether memory clears on each chat
     :memory-size - The configured memory size (for reset logic)"
  [{:keys [system-prompt context tools model memory-size]}]
  (try
    (when-not model
      (throw (ex-info "Model is required" {:missing :model})))

    (when-not system-prompt
      (throw (ex-info "System prompt is required" {:missing :system-prompt})))

    (let [;; Use new memory-size logic
          {:keys [memory stateless?]} (create-memory-for-config memory-size)
          ;; Get the actual memory size for reset logic
          actual-memory-size (if stateless?
                               DEFAULT-STATELESS-BUFFER
                               memory-size)
          initialized-memory (if (and context (not stateless?))
                               (initialize-memory-with-context! memory context)
                               memory)
          ai-service-data {:memory initialized-memory
                           :model model
                           :tools (or tools [])
                           :system-message system-prompt}
          service (-> (chain/create-service AiService ai-service-data)
                      (.build))]
      (assoc ai-service-data
             :service service
             :stateless? stateless?
             :memory-size actual-memory-size
             :context context))
    (catch Exception e
      (log/error e "Failed to create general agent service")
      (throw e))))

(defn chat-with-agent
  "Send a message to the agent and get a response.
   
   Args:
   - agent: The agent map returned by create-general-agent
   - prompt: The user's message/prompt
   
   Returns: A map with :result (the response) and :error (boolean)"
  [agent prompt]
  (if (string/blank? prompt)
    {:result "Error: Cannot process empty prompt"
     :error true}
    (try
      ;; Clear memory for stateless agents at start of each chat
      (when (:stateless? agent)
        (.clear (:memory agent))
        (when (:context agent)
          (initialize-memory-with-context! (:memory agent) (:context agent))))

      ;; For persistent agents, use reset logic to prevent invalid chats
      ;; Reset when approaching memory limit to maintain conversation coherence
      (when-not (:stateless? agent)
        (reset-memory-if-needed! (:memory agent)
                                 (:context agent)
                                 (:memory-size agent)))

      (let [result (.chat (:service agent) prompt)]
        {:result result
         :error false})
      (catch Exception e
        (log/error e "Error during agent chat")
        {:result (str "Error: " (.getMessage e))
         :error true}))))

(defn update-agent-context
  "Update the context of an existing agent and reinitialize memory.
   
   Args:
   - agent: The agent map
   - new-context: New list of context strings
   
   Returns: The updated agent map"
  [agent new-context]
  (let [cleared-memory (doto (:memory agent) (.clear))
        initialized-memory (initialize-memory-with-context! cleared-memory new-context)]
    (assoc agent :context new-context :memory initialized-memory)))

(defn add-tools
  "Add additional tools to an existing agent.
   Note: This requires recreating the service.
   
   Args:
   - agent: The agent map
   - new-tools: Vector of new tools to add
   
   Returns: A new agent map with additional tools"
  [agent new-tools]
  (create-general-agent
   (-> agent
       (select-keys [:system-prompt :context :memory :model :memory-size])
       (assoc :tools (vec (concat (:tools agent) new-tools))))))
