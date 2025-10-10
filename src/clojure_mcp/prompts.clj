(ns clojure-mcp.prompts
  "Prompt definitions for the MCP server"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [pogonos.core :as pg]
            [clojure-mcp.config :as config]
            [clojure-mcp.tools.scratch-pad.tool :as scratch-pad]
            [clojure-mcp.tools.scratch-pad.core :as scratch-pad-core]))

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
  {:name "ACT/add-dir"
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

(defn scratch-pad-load [nrepl-client-atom]
  {:name "ACT/scratch_pad_load"
   :description "Loads a file into the scratch pad state. Returns status messages and a shallow inspect of the loaded data."
   :arguments [{:name "file_path"
                :description "Optional file path: default scratch_pad.edn"
                :required? false}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [working-directory (config/get-nrepl-user-dir @nrepl-client-atom)
                      file-path (get request-args "file_path")
                      filename (if (str/blank? file-path)
                                 (config/get-scratch-pad-file @nrepl-client-atom)
                                 file-path)
                      ;; Handle relative vs absolute paths
                      file (if (and filename (.isAbsolute (io/file filename)))
                             (io/file filename)
                             (scratch-pad/scratch-pad-file-path working-directory filename))]
                  (try
                    ;; Load the file
                    (if (.exists file)
                      (let [data (clojure.edn/read-string (slurp file))
                            ;; Update the scratch pad atom
                            _ (scratch-pad/update-scratch-pad! nrepl-client-atom (constantly data))
                            ;; Get shallow inspect of the data
                            inspect-result (:result (scratch-pad-core/execute-inspect data 1 nil))]
                        (clj-result-k
                         {:description (str "Loaded scratch pad from: " (.getPath file))
                          :messages [{:role :assistant
                                      :content (format "Successfully loaded scratch pad from '%s'.\n\nShallow inspect of loaded data:\n%s"
                                                       (.getPath file)
                                                       (:tree inspect-result))}]}))
                      ;; File doesn't exist
                      (clj-result-k
                       {:description (str "File not found: " (.getPath file))
                        :messages [{:role :assistant
                                    :content (format "The file '%s' does not exist. No data was loaded into the scratch pad."
                                                     (.getPath file))}]}))
                    (catch Exception e
                      ;; Error loading or parsing file
                      (clj-result-k
                       {:description (str "Error loading file: " (.getMessage e))
                        :messages [{:role :assistant
                                    :content (format "Failed to load scratch pad from '%s'.\nError: %s\n\nThe file may be corrupted or contain invalid EDN data."
                                                     (.getPath file)
                                                     (.getMessage e))}]})))))})

(defn scratch-pad-save-as [nrepl-client-atom]
  {:name "ACT/scratch_pad_save_as"
   :description "Saves the current scratch pad state to a specified file."
   :arguments [{:name "file_path"
                :description "File path: relative to .clojure-mcp/ directory"
                :required? true}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [working-directory (config/get-nrepl-user-dir @nrepl-client-atom)
                      file-path (get request-args "file_path")]
                  (if (str/blank? file-path)
                    (clj-result-k
                     {:description "Missing required file_path"
                      :messages [{:role :assistant
                                  :content "Error: file_path is required. Please specify where to save the scratch pad data."}]})
                    (let [;; Handle relative vs absolute paths
                          file (if (.isAbsolute (io/file file-path))
                                 (io/file file-path)
                                 (scratch-pad/scratch-pad-file-path working-directory file-path))
                          ;; Get current scratch pad data
                          current-data (scratch-pad/get-scratch-pad nrepl-client-atom)]
                      (try
                        ;; Create parent directory if needed
                        (let [dir (.getParentFile file)]
                          (when-not (.exists dir)
                            (.mkdirs dir)))
                        ;; Save the data
                        (spit file (pr-str current-data))
                        ;; Get shallow inspect for confirmation
                        (let [inspect-result (:result (scratch-pad-core/execute-inspect current-data 1 nil))]
                          (clj-result-k
                           {:description (str "Saved scratch pad to: " (.getPath file))
                            :messages [{:role :assistant
                                        :content (format "Successfully saved scratch pad to '%s'.\n\nShallow inspect of saved data:\n%s"
                                                         (.getPath file)
                                                         (:tree inspect-result))}]}))
                        (catch Exception e
                          ;; Error saving file
                          (clj-result-k
                           {:description (str "Error saving file: " (.getMessage e))
                            :messages [{:role :assistant
                                        :content (format "Failed to save scratch pad to '%s'.\nError: %s"
                                                         (.getPath file)
                                                         (.getMessage e))}]})))))))})

(def save-new-prompt
  {:name "save_new_prompt"
   :description "Asks the user for a new prompt and a name, and saves them to their user config"
   :prompt-fn (fn [_ request-args clj-result-k]
                (clj-result-k
                 {:description "Help user create/update a custom prompt"
                  :messages [{:role :user
                              :content "Here is how you create a new prompt:
1. In the `.clojure-mcp` project folder, find the `config.edn` file.
2. Under the `:prompts` key will be a map of strings (which name the prompts) and maps (which define the prompts).
3. Note that each prompt has a description and content, and may also have arguments.
4. Read each prompt, and ask the user if they want to create a new prompt or edit an existing prompt.
5. Ask the user what they want the prompt to do.
6. Help the user compose the content of the prompt, including any arguments, if required.
7. If this is a new prompt, save it as described below.
8. If this is an existing prompt, update the existing prompt.

How to save a prompt:

Save prompt in the `.clojure-mcp` project folder, in the `config.edn` file under the `:prompts` key using the STRING name of the prompt as the key,
and using the following format:
`:description` - \"Custom user-added prompt\"
`:content` - %s
`:args` - vector of args, where each arg is a map of `:name`, `:description`, and a `:required?` flag."}]}))})

(defn create-prompt-from-config
  "Creates a prompt from configuration map.
   Config should have :description, :args, and either :file-path or :content.
   Uses pogonos/mustache templating for the content."
  [prompt-name {:keys [description args file-path content]} working-dir nrepl-client-atom]
  (let [;; Prepare arguments structure for MCP
        arguments (mapv (fn [{:keys [name description required?]}]
                          {:name name
                           :description description
                           :required? (boolean required?)})
                        args)
        ;; Get the template content
        template-content (cond
                           content content
                           file-path (let [full-path (if (.isAbsolute (io/file file-path))
                                                       file-path
                                                       (.getCanonicalPath (io/file working-dir file-path)))]
                                       (when (.exists (io/file full-path))
                                         (slurp full-path)))
                           :else nil)]
    (when template-content
      {:name prompt-name
       :description description
       :arguments arguments
       :prompt-fn (fn [_ request-args clj-result-k]
                    (try
                      ;; Convert string keys to keyword keys for pogonos
                      (let [template-data (into {} (map (fn [[k v]] [(keyword k) v]) request-args))
                            rendered-content (pg/render-string template-content template-data)]
                        (clj-result-k
                         {:description description
                          :messages [{:role :user :content rendered-content}]}))
                      (catch Exception e
                        (clj-result-k
                         {:description (str "Error rendering prompt: " (.getMessage e))
                          :messages [{:role :assistant
                                      :content (str "Failed to render prompt template: " (.getMessage e))}]}))))})))

(defn create-prompts-from-config
  "Creates prompts from configuration map.
   Returns a seq of prompt maps."
  [prompts-config working-dir nrepl-client-atom]
  (keep
   (fn [[prompt-name config]]
     (create-prompt-from-config (name prompt-name) config working-dir nrepl-client-atom))
   prompts-config))

(defn default-prompts
  "Returns the default prompts as a list."
  [nrepl-client-atom working-dir]
  [{:name "clojure_repl_system_prompt"
    :description "Provides instructions and guidelines for Clojure development, including style and best practices."
    :arguments []
    :prompt-fn (simple-content-prompt-fn
                "System Prompt: Clojure REPL"
                (str
                 (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_repl_form_edit.md")
                 (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_form_edit.md")))}
   save-new-prompt
   (create-project-summary working-dir)
   chat-session-summary
   resume-chat-session
   plan-and-execute
   (add-dir nrepl-client-atom)
   (scratch-pad-load nrepl-client-atom)
   (scratch-pad-save-as nrepl-client-atom)])

(defn make-prompts
  "Creates all prompts for the MCP server, combining defaults with configuration.
   Config prompts can override defaults by using the same name."
  [nrepl-client-atom]
  (let [working-dir (config/get-nrepl-user-dir @nrepl-client-atom)

        ;; Get default prompts
        default-prompts-list (default-prompts nrepl-client-atom working-dir)

        ;; Get configured prompts from config
        config-prompts (config/get-prompts @nrepl-client-atom)
        config-prompts-list (when config-prompts
                              (create-prompts-from-config
                               config-prompts
                               working-dir
                               nrepl-client-atom))

        ;; Merge prompts, with config overriding defaults by name
        all-prompts (if config-prompts-list
                      (let [config-names (set (map :name config-prompts-list))
                            filtered-defaults (remove #(contains? config-names (:name %))
                                                      default-prompts-list)]
                        (concat filtered-defaults config-prompts-list))
                      default-prompts-list)]

    ;; Return all prompts - filtering happens in core.clj
    all-prompts))
