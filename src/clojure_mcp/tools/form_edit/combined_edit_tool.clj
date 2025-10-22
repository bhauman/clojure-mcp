(ns clojure-mcp.tools.form-edit.combined-edit-tool
  "Implementation of a unified form editing tool.
   This tool combines the functionality of replace, insert_before, and insert_after
   into a single tool with an operation parameter."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.form-edit.core :as core]
   [clojure-mcp.tools.form-edit.pipeline :as pipeline]
   [clojure-mcp.utils.valid-paths :as valid-paths]
   [clojure-mcp.config :as config]
   [clojure.string :as str]
   [rewrite-clj.parser :as p]
   [rewrite-clj.node :as n]))

(defn validate-file-path
  "Validates that a file path is provided, within allowed directories, and is a Clojure file"
  [inputs nrepl-client-atom]
  (let [{:keys [file_path]} inputs
        nrepl-client @nrepl-client-atom]
    (when-not file_path
      (throw (ex-info "Missing required parameter: file_path"
                      {:inputs inputs})))
    (when-not (valid-paths/clojure-file? file_path)
      (throw (ex-info "File must have a Clojure extension (.clj, .cljs, .cljc, .bb, .edn)"
                      {:file_path file_path})))
    ;; Use the utils/validate-path-with-client function to ensure path is valid
    (valid-paths/validate-path-with-client file_path nrepl-client)))

;; Tool creation function
(defn create-edit-form-tool
  "Creates the tool configuration for unified form editing operations.
   Automatically inherits emacs notification preferences from the client."
  [nrepl-client-atom]
  (let [client @nrepl-client-atom
        emacs-notify (config/get-emacs-notify client)]
    {:tool-type :clojure-edit-form
     :nrepl-client-atom nrepl-client-atom
     :enable-emacs-notifications emacs-notify}))

;; Tool name implementation
(defmethod tool-system/tool-name :clojure-edit-form [_]
  "clojure_edit")

;; Tool description implementation
(defmethod tool-system/tool-description :clojure-edit-form [_]
  "Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file using the specified operation.
   
PREFER this unified tool over generic file editing tools for Clojure files (`.clj` `.cljs` `.cljc` `.bb`)

This tool MAKES it EASIER to match a definition that exists in the file AS you only have to match the type of definition `form_type` and the complete identifier `form_identifier` of the definition. This prevents the repeated mismatch errors that occur when trying match an entire string of text for replacement.
This tool validates the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.
This tool reduces the number of tokens that need to be generated.
 
WARNING: you will receive errors if the syntax is wrong, the most common error is an extra or missing parenthesis in the `content`, so be careful with parenthesis.

   Operations:
   - \"replace\": Replaces the form with new content
   - \"insert_before\": Inserts content before the form
   - \"insert_after\": Inserts content after the form
   
   The form is identified by its type (defn, def, deftest, s/def, ns, defmethod etc.) and complete identifier.
   
   Example: Replace a function definition:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"example-fn\"
   - form_type: \"defn\"
   - operation: \"replace\"
   - content: \"(defn example-fn [x] (* x 2))\"
   
   Example: Insert a helper function before a function:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"example-fn\"
   - form_type: \"defn\"
   - operation: \"insert_before\"
   - content: \"(defn helper-fn [x] (* x 2))\"
   
   Example: Insert a test after a function:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"example-fn\"
   - form_type: \"defn\"
   - operation: \"insert_after\"
   - content: \"(deftest example-fn-test\n  (is (= 4 (example-fn 2))))\"
   
   Example: Edit a namespace declaration (form_identifier is the namespace name):
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"my.app.core\"
   - form_type: \"ns\"
   - operation: \"replace\"
   - content: \"(ns my.app.core\n  (:require [clojure.string :as str]\n            [clojure.java.io :as io]))\"
   
Note: For `defmethod` forms, be sure to include the dispatch value (`area :rectangle` or `qualified/area :rectangle`) in the `form_identifier`. Many `defmethod` definitions have qualified names (they include a namespace alias in their identifier like `shape/area`), so it's crucial to use the complete identifier that appears in the file.

   Example: Replace a specific `defmethod` implementation:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"shape/area :square\"
   - form_type: \"defmethod\"
   - operation: \"replace\"
   - content: \"(defmethod shape/area :square [{:keys [w h]}] (* w h))\"

   Example: Work with vector dispatch values:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"convert-length [:feet :inches]\"
   - form_type: \"defmethod\"
   - operation: \"insert_before\"
   - content: \"(def inches-per-foot 12)\"

   The tool will find the form, perform the requested operation, and format the result.
   It returns a diff showing the changes made to the file.")

;; Tool schema implementation
(defmethod tool-system/tool-schema :clojure-edit-form [_]
  {:type "object"
   :required ["file_path" "form_identifier" "form_type" "operation" "content"]
   :properties {"file_path" {:type "string"
                             :description "Path to the file containing the form to edit"}
                "form_identifier" {:type "string"
                                   :description "Complete identifier of the form (e.g., function name, \"shape/area\", or \"convert-length [:feet :inches]\")"}
                "form_type" {:type "string"
                             :description "Type of the form (e.g., \"defn\", \"def\", \"ns\")"}
                "operation" {:type "string"
                             :enum ["replace" "insert_before" "insert_after"]
                             :description "The editing operation to perform"}
                "content" {:type "string"
                           :description "New content to use for the operation"}}})

;; Validate inputs implementation
(defmethod tool-system/validate-inputs :clojure-edit-form [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        {:keys [form_identifier form_type operation content dry_run]} inputs
        form_name form_identifier]
    (when-not form_identifier
      (throw (ex-info "Missing required parameter: form_identifier"
                      {:inputs inputs})))
    (when-not form_type
      (throw (ex-info "Missing required parameter: form_type"
                      {:inputs inputs})))
    (when-not operation
      (throw (ex-info "Missing required parameter: operation"
                      {:inputs inputs})))
    (when-not (contains? #{"replace" "insert_before" "insert_after"} operation)
      (throw (ex-info (str "Invalid operation: " operation
                           ". Supported operations: replace, insert_before, insert_after")
                      {:inputs inputs})))
    (when-not content
      (throw (ex-info "Missing required parameter: content"
                      {:inputs inputs})))
    {:file_path file-path
     :form_name form_name
     :form_type form_type
     :operation operation
     :content content
     :dry_run dry_run}))

;; Execute tool implementation
(defmethod tool-system/execute-tool :clojure-edit-form [{:keys [nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path form_name form_type operation content dry_run]} inputs
        edit-type (case operation
                    "replace" :replace
                    "insert_before" :before
                    "insert_after" :after)
        result (pipeline/edit-form-pipeline file_path form_name form_type content edit-type dry_run tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

;; Format results implementation
(defmethod tool-system/format-results :clojure-edit-form [_ {:keys [error message diff new-source]}]
  (if error
    {:result [message]
     :error true}
    {:result [(or new-source diff)]
     :error false}))

;; Tool registration function
(defn unified-form-edit-tool [nrepl-client-atom]
  (tool-system/registration-map
   (create-edit-form-tool nrepl-client-atom)))
