(ns clojure-mcp.tools.dispatch-agent.core
  "Core implementation for the dispatch agent tool.
   This namespace contains the pure functionality without any MCP-specific code."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-mcp.agent.langchain :as chain]
            [clojure-mcp.tools.project.core :as project-core])
  (:import
   [clojure_mcp.agent.langchain AiService]
   [dev.langchain4j.data.message UserMessage TextContent]))

(declare system-message)

(def MEMORY-SIZE 300)

(defn initialize-memory-with-index! [nrepl-client-atom working-directory memory]
  (let [f (io/file working-directory ".clojure-mcp" "code_index.txt")
        proj-sumary (io/file working-directory "PROJECT_SUMMARY.md")
        ;; Get project info using the cached version
        {:keys [outputs error]} (when nrepl-client-atom
                                  (project-core/inspect-project nrepl-client-atom))]

    (if (.exists f)
      (let [content (slurp f)]
        (doto memory
          (.add (UserMessage.
                 (cond-> []
                   (.exists proj-sumary)
                   (conj (TextContent/from (str "This is a project summary:\n"
                                                (slurp proj-sumary))))
                   ;; Add project info if available
                   (and (not error) outputs)
                   (conj (TextContent/from (str "This is the current project structure:\n"
                                                (string/join "\n" outputs))))
                   content
                   (conj (TextContent/from (str "This is a code index of the code in this project.
Please use it to inform you as to which files should be investigated.\n=======================\n"
                                                content))))))))
      memory)))

(defn initialize-memory-with-files!
  "Initialize memory with content from specific files.
   files - vector of absolute file paths to load"
  [files memory]
  ;; TODO there is no reason why we shouldn't be able to handle images
  (if (seq files)
    (let [contents (for [file-path files
                         :let [file (io/file file-path)]
                         :when (.exists file)]
                     {:path file-path
                      :content (slurp file)})]
      (when (seq contents)
        (.add memory
              (UserMessage.
               (vec (for [{:keys [path content]} contents]
                      (TextContent/from
                       (str "File: " path "\n"
                            "=======================\n"
                            content "\n\n"))))))))
    memory))

(defn initialize-memory!
  "Initialize memory based on context configuration.
   context-config can be:
   - true: use default code index
   - sequential: use specific file paths
   - false/nil: no initialization"
  [nrepl-client-atom working-directory memory context-config]
  (cond
    (true? context-config)
    (initialize-memory-with-index! nrepl-client-atom working-directory memory)

    (sequential? context-config)
    (initialize-memory-with-files! context-config memory)

    :else
    memory))

(defn reset-memory-if-needed! [nrepl-client-atom working-directory memory context-config]
  (if (> (count (.messages memory)) (- MEMORY-SIZE 50))
    (let [cleared-memory (doto memory (.clear))]
      (initialize-memory! nrepl-client-atom working-directory cleared-memory context-config))
    memory))

#_(chain/chat-memory 300)

#_(.contents (first (.messages (doto (chain/chat-memory 300)
                                 (.add (UserMessage. nil "Hello"))))))

(defn create-ai-service
  "Creates an AI service for doing read only tasks.

   Args:
   - model: Optional pre-built langchain model. If nil, uses chain/agent-model
   - memory: Chat memory instance to use
   - tools: Vector of tool functions"
  ([memory tools] (create-ai-service nil memory tools))
  ([model memory tools]
   (try
     (when-let [selected-model (or model
                                   (some-> (chain/agent-model)
                                           (.build)))]
       (let [ai-service-data {:memory memory
                              :model selected-model
                              :tools tools
                              :system-message system-message}
             service (-> (chain/create-service AiService ai-service-data)
                         (.build))]
         (assoc ai-service-data :service service)))
     (catch Exception e
       (log/error e "Failed to create dispatch_agent AI service")
       (throw e)))))

(defn get-ai-service
  "Gets or creates an AI service. Uses a simple cache in nrepl-client-atom.

   Args:
   - tool: Map containing :nrepl-client-atom, :model, :memory and :tools"
  [{:keys [nrepl-client-atom model memory tools] :as _tool}]
  (or (::ai-service @nrepl-client-atom)
      (when-let [ai (create-ai-service model memory tools)]
        (swap! nrepl-client-atom assoc ::ai-service ai)
        ai)))

(defn dispatch-agent
  "Dispatches an agent with the given prompt. The agent will only have access to read tools.
   Returns a string response from the agent.

   Args:
   - tool: Map containing agent configuration
   - prompt: String prompt to send to the agent"
  [{:keys [nrepl-client-atom working-directory context-config] :as tool} prompt]
  (if (string/blank? prompt)
    {:result "Error: Cannot process empty prompt"
     :error true}
    (if-let [ai-service (get-ai-service tool)]
      (do
        (reset-memory-if-needed! nrepl-client-atom
                                 working-directory
                                 (:memory ai-service)
                                 context-config)
        (let [result (.chat (:service ai-service) prompt)]
          {:result result
           :error false}))
      {:result "ERROR: No model configured for this agent."
       :error true})))

(defn validate-dispatch-agent-inputs
  "Validates inputs for the dispatch-agent function"
  [inputs]
  (cond
    (nil? inputs)
    (throw (ex-info "Missing inputs" {:error-details ["No input parameters provided"]}))

    (nil? (:prompt inputs))
    (throw (ex-info "Missing required parameter: prompt"
                    {:error-details ["The 'prompt' parameter is required"]}))

    (not (string? (:prompt inputs)))
    (throw (ex-info "Parameter 'prompt' must be a string"
                    {:error-details [(str "Got: " (type (:prompt inputs)))]}))

    :else
    inputs))

(comment
  ;; Example usage with custom model
  (let [user-dir (System/getProperty "user.dir")
        client-atom (atom {:clojure-mcp.config/config
                           {:nrepl-user-dir user-dir
                            :allowed-directories [user-dir]}})
        ;; Use default model
        ai-default (create-ai-service client-atom)
        ;; Use custom model
        custom-model (->
                      (chain/create-gemini-reasoning-model "gemini-2.5-pro" :medium)
                      #_(chain/create-anthropic-model "claude-3-7-sonnet-20250219")
                      (.build))
        ai-custom (create-ai-service client-atom custom-model)]

    ;; Test with default model
    #_(.chat (:service ai-default) "find langchain integration code")

    ;; Test with custom model  
    (.chat (:service ai-custom) "find the langchain integration code")))

(def system-message
  "You are an agent for a Clojure Coding Assistant. Given the user's prompt, you should use the tools available to you to answer the user's question.

You MAY be provided with a project summary and a code-index... Please use these as a starting poing to answering the provided question.

Notes:
1. IMPORTANT: You should be concise, direct, and to the point, since your responses will be displayed on a command line interface. Answer the user's question directly, without elaboration, explanation, or details. One word answers are best. Avoid introductions, conclusions, and explanations. You MUST avoid text before/after your response, such as \"The answer is <answer>.\", \"Here is the content of the file...\" or \"Based on the information provided, the answer is...\" or \"Here is what I will do next...\".
2. When relevant, share file names and code snippets relevant to the query
3. Any file paths you return in your final response MUST be absolute. DO NOT use relative paths.")
