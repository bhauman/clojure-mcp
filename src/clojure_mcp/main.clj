(ns clojure-mcp.main
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure-mcp.core :as core]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.prompts :as prompts]
            [clojure-mcp.tools.project.core :as project]
            [clojure-mcp.resources :as resources]
            [clojure-mcp.config :as config]
            ;; tools
            [clojure-mcp.tools.directory-tree.tool :as directory-tree-tool]
            [clojure-mcp.tools.eval.tool :as eval-tool]
            [clojure-mcp.tools.unified-read-file.tool :as unified-read-file-tool]
            [clojure-mcp.tools.grep.tool :as new-grep-tool]
            [clojure-mcp.tools.glob-files.tool :as glob-files-tool]
            [clojure-mcp.tools.think.tool :as think-tool]
            [clojure-mcp.tools.bash.tool :as bash-tool]
            [clojure-mcp.tools.form-edit.combined-edit-tool :as combined-edit-tool]
            [clojure-mcp.tools.form-edit.tool :as new-form-edit-tool]
            [clojure-mcp.tools.file-edit.tool :as file-edit-tool]
            [clojure-mcp.tools.file-write.tool :as file-write-tool]
            [clojure-mcp.tools.dispatch-agent.tool :as dispatch-agent-tool]
            [clojure-mcp.tools.architect.tool :as architect-tool]
            [clojure-mcp.tools.code-critique.tool :as code-critique-tool]
            [clojure-mcp.tools.project.tool :as project-tool]
            [clojure-mcp.tools.scratch-pad.tool :as scratch-pad-tool]))

;; Define the resources you want available
(defn make-resources [nrepl-client-atom working-dir]
  (keep
   identity
   [(resources/create-file-resource
     "custom://project-summary"
     "PROJECT_SUMMARY.md"
     "A Clojure project summary document for the project hosting the REPL, this is intended to provide the LLM with important context to start."
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "PROJECT_SUMMARY.md")))
    (resources/create-file-resource
     "custom://readme"
     "README.md"
     "A README document for the current Clojure project hosting the REPL"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "README.md")))
    (resources/create-file-resource
     "custom://claude"
     "CLAUDE.md"
     "The Claude instructions document for the current project hosting the REPL"
     "text/markdown"
     (.getCanonicalPath (io/file working-dir "CLAUDE.md")))
    (resources/create-file-resource
     "custom://llm-code-style"
     "LLM_CODE_STYLE.md"
     "Guidelines for writing Clojure code for the current project hosting the REPL"
     "text/markdown"
     (str working-dir "/LLM_CODE_STYLE.md"))
    (let [{:keys [outputs error]} (project/inspect-project @nrepl-client-atom)]
      (when-not error
        (resources/create-string-resource
         "custom://project-info"
         "Clojure Project Info"
         "Information about the current Clojure project structure, attached REPL environment and dependencies"
         "text/markdown"
         outputs)))]))

(defn make-prompts [nrepl-client-atom working-dir]
  [{:name "clojure_repl_system_prompt"
    :description "Provides instructions and guidelines for Clojure development, including style and best practices."
    :arguments [] ;; No arguments needed for this prompt
    :prompt-fn (prompts/simple-content-prompt-fn
                "System Prompt: Clojure REPL"
                (str
                 (prompts/load-prompt-from-resource "clojure-mcp/prompts/system/clojure_repl_form_edit.md")
                 (prompts/load-prompt-from-resource "clojure-mcp/prompts/system/clojure_form_edit.md")))}
   (prompts/create-project-summary working-dir)
   prompts/chat-session-summary
   prompts/resume-chat-session
   prompts/plan-and-execute
   (prompts/add-dir nrepl-client-atom)])

(defn make-tools [nrepl-client-atom working-directory]
  [;; read-only tools
   (directory-tree-tool/directory-tree-tool nrepl-client-atom)
   (unified-read-file-tool/unified-read-file-tool nrepl-client-atom)
   (new-grep-tool/grep-tool nrepl-client-atom)
   (glob-files-tool/glob-files-tool nrepl-client-atom)
   (think-tool/think-tool nrepl-client-atom)
   ;; experimental todo list / scratch pad
   (scratch-pad-tool/scratch-pad-tool nrepl-client-atom)

   ;; eval
   (eval-tool/eval-code nrepl-client-atom)
   ;; now runs in the nrepl process
   (bash-tool/bash-tool nrepl-client-atom)

   ;; editing tools
   (combined-edit-tool/unified-form-edit-tool nrepl-client-atom)
   (new-form-edit-tool/sexp-replace-tool nrepl-client-atom)
   (file-edit-tool/file-edit-tool nrepl-client-atom)
   (file-write-tool/file-write-tool nrepl-client-atom)

   ;; introspection
   (project-tool/inspect-project-tool nrepl-client-atom)

   ;; Agents these are read only
   ;; these require api keys to be configured
   (dispatch-agent-tool/dispatch-agent-tool nrepl-client-atom)
   ;; not sure how useful this is
   (architect-tool/architect-tool nrepl-client-atom)

   ;; experimental 
   (code-critique-tool/code-critique-tool nrepl-client-atom)])

;; DEPRECATED but maintained for backword compatability
(defn ^:deprecated my-prompts
  ([working-dir]
   (my-prompts working-dir core/nrepl-client-atom))
  ([working-dir nrepl-client-atom]
   (make-prompts nrepl-client-atom working-dir)))

(defn ^:deprecated my-resources [nrepl-client-atom working-dir]
  (make-resources nrepl-client-atom working-dir))

(defn ^:deprecated my-tools [nrepl-client-atom]
  (let [working-directory (config/get-nrepl-user-dir @nrepl-client-atom)]
    (make-tools nrepl-client-atom working-directory)))

(defn start-mcp-server [opts]
  (core/build-and-start-mcp-server
   opts
   {:make-tools-fn make-tools
    :make-prompts-fn make-prompts
    :make-resources-fn make-resources}))

;; not sure if this is even needed

;; start the server

;; Example parameterized prompt
(defn code-review-prompt-example []
  {:name "code-review-prompt"
   :description "Generate a code review prompt for a specific file or namespace"
   :arguments [{:name "file-path"
                :description "The file path to review"
                :required? true}
               {:name "focus-areas"
                :description "Specific areas to focus on (e.g., 'performance,style,testing')"
                :required? false}]
   :prompt-fn (fn [_ request-args clj-result-k]
                (let [file-path (get request-args "file-path")
                      focus-areas (get request-args "focus-areas" "general code quality")]
                  (clj-result-k
                   {:description (str "Code review for: " file-path)
                    :messages
                    [{:role :user
                      :content
                      (str "Please perform a thorough code review of the file at: "
                           file-path "\n\n"
                           "Focus areas: " focus-areas "\n\n"
                           "Consider:\n"
                           "1. Code style and Clojure idioms\n"
                           "2. Performance implications\n"
                           "3. Error handling\n"
                           "4. Function complexity and readability\n"
                           "5. Missing tests or edge cases\n\n"
                           "Please use the read_file tool to examine the code, "
                           "then provide detailed feedback.")}]})))})

