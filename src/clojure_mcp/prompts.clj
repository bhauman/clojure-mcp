(ns clojure-mcp.prompts
  "Prompt definitions for the MCP server"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [pogonos.core :as pg]
            [clojure-mcp.config :as config])) ; Added config require

(defn simple-content-prompt-fn
  "Returns a prompt-fn that ignores request arguments and returns
   a fixed description and a single assistant message with the given content."
  [description content]
  (fn [_ _ clj-result-k]
    (clj-result-k
     {:description description
      :messages [{:role :assistant :content content}]})))

(defn load-prompt-from-resource
  "Loads prompt content from a classpath resource file."
  [filename]
  (if-let [resource (io/resource filename)]
    (slurp resource)
    (str "Error: Prompt file not found on classpath: " filename)))

;; --- Prompt Definitions ---

(defn create-project-summary [working-dir]
  {:name "create-update-project-summary"
   :description "Generates a prompt instructing the LLM to create a summary of a project."
   :arguments []
   :prompt-fn (fn [_ _ clj-result-k]
                (if (and working-dir
                         (let [f (io/file working-dir)]
                           (and (.exists f)
                                (.isDirectory f))))
                  (clj-result-k
                   {:description (str "Create project summary for: " working-dir)
                    :messages [{:role :user
                                :content
                                (pg/render-resource "clojure-mcp/prompts/create_project_summary.md"
                                                    {:root-directory
                                                     working-dir})}]})
                  (clj-result-k
                   {:description (str "Root directory not found.")
                    :messages [{:role :user
                                :content
                                (str "Root directory not provided So this will not be a prompt." "::" working-dir "::")}]})))})

(def clojure-system-repl-form-edit
  {:name "clojure_repl_system_prompt"
   :description "Provides instructions and guidelines for Clojure development, including style and best practices."
   :arguments [] ;; No arguments needed for this prompt
   :prompt-fn (simple-content-prompt-fn
               "System Prompt: Clojure REPL"
               (str
                (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_repl_form_edit.md")
                (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_form_edit.md")))})

(def clojure-spec-driven-modifier
  {:name "clj-spec-driven-modifier"
   :description "Spec first modifer for REPL-driven development"
   :arguments [] ;; No arguments needed
   :prompt-fn (simple-content-prompt-fn
               "Spec-Driven-Development Modifier for Clojure"
               (load-prompt-from-resource "clojure-mcp/prompts/spec_modifier.md"))})

(def clojure-test-driven-modifier
  {:name "clj-test-driven-modifier"
   :description "Test driven modifer for REPL-driven development"
   :arguments [] ;; No arguments needed
   :prompt-fn (simple-content-prompt-fn
               "Test-Driven-Development Modifier for Clojure"
               (load-prompt-from-resource "clojure-mcp/prompts/test_modifier.md"))})

#_(def incremental-file-creation
    {:name "incremental_file_creation"
     :description "Guide for creating Clojure files incrementally to maximize success."
     :arguments [] ;; No arguments needed for this prompt
     :prompt-fn (simple-content-prompt-fn
                 "Incremental File Creation for Clojure"
                 (load-prompt-from-resource "clojure-mcp/prompts/system/incremental_file_creation.md"))})

(def scratch-pad-guide
  {:name "use-scratch-pad"
   :description "Guide for using the scratch pad tool for persistent storage and task tracking"
   :arguments []
   :prompt-fn (simple-content-prompt-fn
               "Use Scratch Pad"
               "Let's use the scratch_pad tool.\n\nThe scratch_pad tool is your persistent storage for data between tool calls. Use it to:\n\n1. **Track Tasks**: Create todo lists to manage complex workflows\n2. **Store Intermediate Results**: Save computation results for later use\n3. **Share Context**: Pass data between different agents or tool sequences\n4. **Build Complex Data**: Incrementally construct data structures\n\nExample todo workflow:\n```clojure\n;; Add tasks\nscratch_pad(op: set_path, path: [\"todos\"], \n  value: {0: {task: \"Analyze code\", done: false},\n          1: {task: \"Write tests\", done: false}})\n\n;; Check off completed\nscratch_pad(op: set_path, path: [\"todos\" 0 \"done\"], value: true)\n\n;; View progress\nscratch_pad(op: tree_view)\n```\n\nBest practices:\n- Use descriptive keys for organization\n- Store results you'll need later\n- Track progress on multi-step tasks\n- Clean up completed items when done")})

(def plan-and-execute
  {:name "plan-and-execute"
   :description "Use the scratch pad tool to plan and execute an change"
   :arguments []
   :prompt-fn (simple-content-prompt-fn
               "Plan and Execute"
               "I'd like you to make a Plan using the scratch_pad tool. 

1. Determine questions that need answers
2. Research the answers to those questions using the tools available
3. Create a list of Tasks
4. Execute the Tasks updating them 
5. Go back to Step 1 if more questions and research are needed to accomplish the goal

Create and execute the plan to accomplish the following query")})

(def chat-session-summary
  {:name "chat-session-summarize"
   :description "Instructs the assistant to create a summary of the current chat session and store it in the scratch pad. `chat_session_key` is optional and will default to `chat_session_summary`"
   :arguments [{:name "chat_session_key"
                :description "[Optional] key to store the session summary in"
                :required? false}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [provided-key (get request-args "chat_session_key")
                      session-key (if (str/blank? provided-key)
                                    "chat_session_summary"
                                    provided-key)]
                  (clj-result-k
                   {:description (str "Create conversation summary for key: " session-key)
                    :messages [{:role :user
                                :content (format "Place in the scratch_pad under the key path [\"%s\"] a detailed but concise summary of our conversation above. Focus on information that would be helpful for continuing the conversation, including what we did, what we're doing, which files we're working on, and what we're going to do next."
                                                 session-key)}]})))})

(def resume-chat-session
  {:name "chat-session-resume"
   :description "Instructs the assistant to resume a previous chat session by loading context from the scratch pad. `chat_session_key` is optional and will default to `chat_session_summary`"
   :arguments [{:name "chat_session_key"
                :description "[Optional] key where session summary is stored"
                :required? false}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [provided-key (get request-args "chat_session_key")
                      session-key (if (str/blank? provided-key)
                                    "chat_session_summary"
                                    provided-key)]
                  (clj-result-k
                   {:description (str "Resume conversation from key: " session-key)
                    :messages [{:role :user
                                :content (format "We are continuing a previous chat session, can you the read the following context
* read the PROJECT_SUMMARY.md file
* call the clojure_inspect_project tool
Also we stored information about our last conversation in the scratch_pad [\"%s\"]  path so can you call scratch_pad with get_path [\"%s\"] to see what we were working on previously.
After doing this provide a very brief (8 lines) summary of where we are and then wait for my instructions."
                                                 session-key
                                                 session-key)}]})))})

(defn add-dir [nrepl-client-atom]
  {:name "add-dir"
   :description "Adds a directory to the allowed-directories list, giving the LLM access to it"
   :arguments [{:name "directory"
                :description "Directory path to add (can be relative or absolute)"
                :required? true}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [dir-path (get request-args "directory")
                      user-dir (config/get-nrepl-user-dir @nrepl-client-atom)
                      ;; Normalize path similar to config/relative-to
                      normalized-path (try
                                        (let [f (io/file dir-path)]
                                          (if (.isAbsolute f)
                                            (.getCanonicalPath f)
                                            (.getCanonicalPath (io/file user-dir dir-path))))
                                        (catch Exception _
                                          nil))]
                  (if normalized-path
                    (let [dir-file (io/file normalized-path)]
                      (if (.exists dir-file)
                        (if (.isDirectory dir-file)
                          (let [current-dirs (config/get-allowed-directories @nrepl-client-atom)
                                new-dirs (-> (concat current-dirs [normalized-path])
                                             distinct
                                             vec)]
                            (config/set-config! nrepl-client-atom :allowed-directories new-dirs)
                            (clj-result-k
                             {:description (str "Added directory: " normalized-path)
                              :messages [{:role :assistant
                                          :content (format "Directory '%s' has been added to allowed directories. You now have access to read and write files in this directory and its subdirectories."
                                                           normalized-path)}]}))
                          (clj-result-k
                           {:description (str "Path is not a directory: " normalized-path)
                            :messages [{:role :assistant
                                        :content (format "The path '%s' exists but is not a directory. Please provide a valid directory path."
                                                         normalized-path)}]}))
                        (clj-result-k
                         {:description (str "Directory does not exist: " normalized-path)
                          :messages [{:role :assistant
                                      :content (format "The directory '%s' does not exist. Please provide a valid existing directory path."
                                                       normalized-path)}]})))
                    (clj-result-k
                     {:description "Failed to normalize path"
                      :messages [{:role :assistant
                                  :content (format "Failed to normalize the path '%s'. Please check the path and try again."
                                                   dir-path)}]}))))})
