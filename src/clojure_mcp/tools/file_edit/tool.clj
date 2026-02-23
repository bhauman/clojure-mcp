(ns clojure-mcp.tools.file-edit.tool
  "Implementation of the file-edit tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.file-edit.pipeline :as pipeline]
   [clojure-mcp.utils.valid-paths :as valid-paths]))

;; Factory function to create the tool configuration
(defn create-file-edit-tool
  "Creates the file-edit tool configuration"
  [nrepl-client-atom]
  {:tool-type :file-edit
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods for the file-edit tool
(defmethod tool-system/tool-name :file-edit [_]
  "file_edit")

(defmethod tool-system/tool-description :file-edit [_]
  "Edit a file by replacing a specific text string with a new one. For safety, this tool requires that the string to replace appears exactly once in the file. 

FIRST: the clojure_edit tool is prefered because they use precise structural rewrite-clj editing. They also incorporate linting and ensure balance parenthesis.

WHEN the clojure_edit tool won't work or you have a small easy edit 

PREFER the file_write tool for replacing more than half a file, this saves on tokens

For Clojure files (.clj, .cljs, .cljc, .edn):
- Content will be linted for syntax errors before writing
- Content will be formatted according to Clojure standards
- Writing will fail if linting detects syntax errors

To make a file edit, provide the file_path, old_string (the text to replace), and new_string (the replacement text). The old_string must uniquely identify the specific instance you want to change, so include several lines of context before and after the change point. To create a new file, use file_write instead.")

(defmethod tool-system/tool-schema :file-edit [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "The absolute path to the file to modify (must be absolute, not relative)"}
                :old_string {:type :string
                             :description "The text to replace (must match the file contents exactly, including all whitespace and indentation)."}
                :new_string {:type :string
                             :description "The edited text to replace the old_string"}
                :dry_run {:type :string
                          :enum ["diff" "new-source"]
                          :description "Optional. Preview changes without writing: \"diff\" returns unified diff, \"new-source\" returns full modified source."}}
   :required [:file_path :old_string :new_string]})

(defmethod tool-system/validate-inputs :file-edit [{:keys [nrepl-client-atom]} inputs]
  (let [{:keys [file_path old_string new_string]} inputs
        nrepl-client @nrepl-client-atom]

    ;; Check required parameters
    (when-not file_path
      (throw (ex-info "Missing required parameter: file_path" {:inputs inputs})))

    (when-not (contains? inputs :old_string)
      (throw (ex-info "Missing required parameter: old_string" {:inputs inputs})))

    (when-not (contains? inputs :new_string)
      (throw (ex-info "Missing required parameter: new_string" {:inputs inputs})))

    ;; Reject empty old_string - direct users to file_write instead
    (when (empty? old_string)
      (throw (ex-info "Empty old_string is not supported. To create a new file, use file_write instead."
                      {:inputs inputs})))

    ;; Check for identical strings (early rejection)
    (when (= old_string new_string)
      (throw (ex-info "No changes to make: old_string and new_string are exactly the same."
                      {:inputs inputs})))

    ;; Validate dry_run if provided
    (let [dry_run (:dry_run inputs)]
      (when (and (some? dry_run) (not (#{"diff" "new-source"} dry_run)))
        (throw (ex-info (str "Invalid dry_run: " dry_run
                             ". Supported values: diff, new-source")
                        {:inputs inputs}))))

    ;; Validate path using the utility function
    (let [validated-path (valid-paths/validate-path-with-client file_path nrepl-client)]
      ;; Return validated inputs with normalized path
      (assoc inputs
             :file_path validated-path
             :old_string old_string
             :new_string new_string))))

(defmethod tool-system/execute-tool :file-edit [{:keys [_nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path old_string new_string dry_run]} inputs
        result (pipeline/file-edit-pipeline file_path old_string new_string dry_run tool)]
    (pipeline/format-result result)))

(defmethod tool-system/format-results :file-edit [_ {:keys [error message diff new-source type repaired]}]
  (if error
    {:error true
     :result [message]}
    (cond-> {:error false
             :result [(or new-source diff)]
             :type type}
      ;; Include repaired flag if present
      repaired (assoc :repaired true))))

;; Backward compatibility function that returns the registration map
(defn file-edit-tool [nrepl-client-atom]
  (tool-system/registration-map (create-file-edit-tool nrepl-client-atom)))