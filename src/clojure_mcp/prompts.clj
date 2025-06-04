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

;; this is just scratch work for now

(def clojure-system-repl-flex
  {:name "clojure_repl_flex_system_prompt"
   :description "Provides instructions and guidelines for Clojure development, including style and best practices."
   :arguments [] ;; No arguments needed for this prompt
   :prompt-fn (simple-content-prompt-fn
               "System Prompt: Clojure REPL Flex"
               (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_flex.md"))})

(def clojure-system-repl
  {:name "clojure_repl_system_prompt"
   :description "Provides instructions and guidelines for Clojure development, including style and best practices."
   :arguments [] ;; No arguments needed for this prompt
   :prompt-fn (simple-content-prompt-fn
               "System Prompt: Clojure REPL"
               (str
                (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_repl.md")
                (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_clojure_edit_tool_inst.md")))})

(def clojure-system-repl-pattern-edit
  {:name "clojure_repl_system_prompt"
   :description "Provides instructions and guidelines for Clojure development, including style and best practices."
   :arguments [] ;; No arguments needed for this prompt
   :prompt-fn (simple-content-prompt-fn
               "System Prompt: Clojure REPL"
               (str
                (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_repl_pattern_edit.md")
                (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_pattern_edit.md")))})

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

(defn sync-namespace-workflow-prompt [namesp]
  (format "I'm currently working on a Clojure namespace `%s`  

Can you:

1. find it 
2. `require` `:reload` it into the REPL environment 
3. change into the `%s` with `in-ns` 
4. and make an artifact for it

If the file get's *edited* outside and must be read to see the changes, you should `require` :reload the file into the REPL enviromnent."
          namesp
          namesp))

(def clj-sync-namespace
  {:name "clj-sync-namespace"
   :description "Generates a prompt instructing the assistant to synchronize the REPL with a specific namespace (require :reload, in-ns)."
   :arguments [{:name "namespace"
                :description "The fully qualified name of the Clojure namespace to sync."
                :required? true}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [namespace-arg (get request-args "namespace")]
                  (clj-result-k
                   {:description (str "Sync REPL with namespace: " namespace-arg)
                    :messages [{:role :user
                                :content (sync-namespace-workflow-prompt namespace-arg)}]})))})

;; Function to get all prompts for registration with the MCP server
(def clojure-edit-guide
  {:name "clojure_edit_guide"
   :description "Provides specialized guidance for using Clojure structure-aware editing tools instead of text editing."
   :arguments [] ;; No arguments needed for this prompt
   :prompt-fn (simple-content-prompt-fn
               "Clojure Structure-Aware Editing Guide"
               (load-prompt-from-resource "clojure-mcp/prompts/system/clojure_edit.md"))})

(def incremental-file-creation
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
               "Let's use the scratch_pad tool.\n\nThe scratch_pad tool is your persistent storage for data between tool calls. Use it to:\n\n1. **Track Tasks**: Create todo lists to manage complex workflows\n2. **Store Intermediate Results**: Save computation results for later use\n3. **Share Context**: Pass data between different agents or tool sequences\n4. **Build Complex Data**: Incrementally construct data structures\n\nExample todo workflow:\n```clojure\n;; Add tasks\nscratch_pad(op: assoc_in, path: [\"todos\"], \n  value: {0: {task: \"Analyze code\", done: false},\n          1: {task: \"Write tests\", done: false}})\n\n;; Check off completed\nscratch_pad(op: assoc_in, path: [\"todos\" 0 \"done\"], value: true)\n\n;; View progress\nscratch_pad(op: tree_view)\n```\n\nBest practices:\n- Use descriptive keys for organization\n- Store results you'll need later\n- Track progress on multi-step tasks\n- Clean up completed items when done")})
