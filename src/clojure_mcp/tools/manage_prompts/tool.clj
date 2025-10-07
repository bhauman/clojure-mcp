(ns clojure-mcp.tools.manage-prompts.tool
  "Implementation of the manage-prompts tool for saving and managing user prompts in config."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.config :as config]
   [clojure.string :as str]
   [clojure.tools.logging :as log]))

(defmethod tool-system/tool-name :manage-prompts [_]
  "manage_prompts")

(defmethod tool-system/tool-description :manage-prompts [_]
  "Manages user-defined prompts stored in the project configuration file.

Operations:
- add_prompt: Add or update a prompt with a name and content
- remove_prompt: Remove a prompt by name
- list_prompts: List all saved prompts
- get_prompt: Get a specific prompt by name

Prompts are stored in the project config file (.clojure-mcp/config.edn) under the :prompts key.

Use this tool when users want to:
- Save a prompt they've created for later reuse
- View their saved prompts
- Update or remove existing prompts
- Retrieve a specific prompt

Example usage:
- Save: manage_prompts(op: \"add_prompt\", prompt_name: \"code-reviewer\", prompt_content: \"Review the following code for best practices...\")
- List: manage_prompts(op: \"list_prompts\")
- Get: manage_prompts(op: \"get_prompt\", prompt_name: \"code-reviewer\")
- Remove: manage_prompts(op: \"remove_prompt\", prompt_name: \"code-reviewer\")")

(defmethod tool-system/tool-schema :manage-prompts [_]
  {:type "object"
   :properties {"op" {:type "string"
                      :enum ["add_prompt" "remove_prompt" "list_prompts" "get_prompt"]
                      :description "The operation to perform: add_prompt, remove_prompt, list_prompts, or get_prompt"}
                "prompt_name" {:type "string"
                               :description "Name of the prompt (required for add_prompt, remove_prompt, get_prompt)"}
                "prompt_content" {:type "string"
                                  :description "The prompt text content (required for add_prompt)"}}
   :required ["op"]})

(defmethod tool-system/validate-inputs :manage-prompts [_ {:keys [op prompt_name prompt_content] :as inputs}]
  (when-not op
    (throw (ex-info "Missing required parameter: op" {:inputs inputs})))

  (when-not (#{"add_prompt" "remove_prompt" "list_prompts" "get_prompt"} op)
    (throw (ex-info "Invalid operation. Must be one of: add_prompt, remove_prompt, list_prompts, get_prompt"
                    {:op op :inputs inputs})))

  ;; Operation-specific validation
  (case op
    "add_prompt" (do
                   (when-not prompt_name
                     (throw (ex-info "Missing required parameter for add_prompt: prompt_name" {:inputs inputs})))
                   (when-not prompt_content
                     (throw (ex-info "Missing required parameter for add_prompt: prompt_content" {:inputs inputs}))))
    ("remove_prompt" "get_prompt") (when-not prompt_name
                                     (throw (ex-info (str "Missing required parameter for " op ": prompt_name")
                                                     {:inputs inputs})))
    "list_prompts" nil) ;; no additional validation needed

  inputs)

(defmethod tool-system/execute-tool :manage-prompts [{:keys [working-dir]} {:keys [op prompt_name prompt_content]}]
  (try
    (let [result (case op
                   "add_prompt"
                   (let [current-config (config/load-project-config working-dir)
                         updated-config (assoc-in current-config [:prompts prompt_name] prompt_content)]
                     (config/save-project-config updated-config working-dir)
                     {:message (str "Prompt '" prompt_name "' saved successfully")
                      :prompt_name prompt_name})

                   "remove_prompt"
                   (let [current-config (config/load-project-config working-dir)]
                     (if (get-in current-config [:prompts prompt_name])
                       (let [updated-config (update current-config :prompts dissoc prompt_name)]
                         (config/save-project-config updated-config working-dir)
                         {:message (str "Prompt '" prompt_name "' removed successfully")
                          :prompt_name prompt_name})
                       {:message (str "Prompt '" prompt_name "' not found")
                        :prompt_name prompt_name}))

                   "list_prompts"
                   (let [current-config (config/load-project-config working-dir)
                         prompts (:prompts current-config)]
                     {:prompts (or prompts {})
                      :count (count (or prompts {}))})

                   "get_prompt"
                   (let [current-config (config/load-project-config working-dir)
                         prompt (get-in current-config [:prompts prompt_name])]
                     (if prompt
                       {:prompt_name prompt_name
                        :prompt_content prompt}
                       {:message (str "Prompt '" prompt_name "' not found")
                        :prompt_name prompt_name})))]
      {:result result
       :error false})
    (catch Exception e
      (log/error e (str "Error executing manage_prompts operation: " (.getMessage e)))
      {:error true
       :result (str "Error: " (.getMessage e))})))

(defmethod tool-system/format-results :manage-prompts [_ {:keys [error result]}]
  (if error
    {:result [result]
     :error true}
    (let [formatted (case (:message result)
                      nil
                      ;; list_prompts or get_prompt with results
                      (if (:prompts result)
                        (if (empty? (:prompts result))
                          "No prompts saved yet."
                          (str "Saved prompts (" (:count result) "):\n"
                               (str/join "\n"
                                         (map (fn [[k v]]
                                                (let [desc (if (map? v)
                                                             (or (:description v)
                                                                 (:content v)
                                                                 (str v))
                                                             (str v))
                                                      preview (subs desc 0 (min 60 (count desc)))]
                                                  (str "- " k ": " preview
                                                       (when (> (count desc) 60) "..."))))
                                              (:prompts result)))))
                        ;; get_prompt with result
                        (let [content (:prompt_content result)]
                          (str "Prompt: " (:prompt_name result) "\n\n"
                               (if (map? content)
                                 (str/join "\n"
                                           [(when (:description content)
                                              (str "Description: " (:description content)))
                                            (when (:content content)
                                              (str "Content:\n" (:content content)))
                                            (when (:file-path content)
                                              (str "File: " (:file-path content)))])
                                 content))))
                      ;; Messages from add/remove operations or not found
                      (:message result))]
      {:result [formatted]
       :error false})))

(defn create-manage-prompts-tool [nrepl-client-atom]
  (let [working-dir (config/get-nrepl-user-dir @nrepl-client-atom)]
    {:tool-type :manage-prompts
     :nrepl-client-atom nrepl-client-atom
     :working-dir working-dir}))

(defn manage-prompts-tool
  "Returns the registration map for the manage-prompts tool.

   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-manage-prompts-tool nrepl-client-atom)))
