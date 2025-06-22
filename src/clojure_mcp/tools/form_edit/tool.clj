(ns clojure-mcp.tools.form-edit.tool
  "Implementation of form editing tools using the tool-system multimethod approach.
   Includes tools for:
   - Editing top-level forms (replace, insert before/after)
   - Editing docstrings
   - Editing comment blocks
   - Generating file outlines"
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.form-edit.core :as core]
   [clojure-mcp.tools.form-edit.pipeline :as pipeline]
   [clojure-mcp.utils.valid-paths :as valid-paths]
   [clojure-mcp.config :as config]
   [clojure.string :as str]
   [rewrite-clj.parser :as p]
   [rewrite-clj.node :as n]))

;; Factory functions to create the tool configurations

(defn create-edit-replace-form-tool
  "Creates the tool configuration for replacing forms in a file.
   Automatically inherits emacs notification preferences from the client."
  [nrepl-client-atom]
  (let [client @nrepl-client-atom
        emacs-notify (config/get-emacs-notify client)]
    {:tool-type :clojure-edit-replace-form
     :nrepl-client-atom nrepl-client-atom
     :enable-emacs-notifications emacs-notify}))

(defn create-edit-insert-before-form-tool
  "Creates the tool configuration for inserting content before forms.
   Automatically inherits emacs notification preferences from the client."
  [nrepl-client-atom]
  (let [client @nrepl-client-atom
        emacs-notify (config/get-emacs-notify client)]
    {:tool-type :clojure-edit-insert-before-form
     :nrepl-client-atom nrepl-client-atom
     :enable-emacs-notifications emacs-notify}))

(defn create-edit-insert-after-form-tool
  "Creates the tool configuration for inserting content after forms.
   Automatically inherits emacs notification preferences from the client."
  [nrepl-client-atom]
  (let [client @nrepl-client-atom
        emacs-notify (config/get-emacs-notify client)]
    {:tool-type :clojure-edit-insert-after-form
     :nrepl-client-atom nrepl-client-atom
     :enable-emacs-notifications emacs-notify}))

(defn create-edit-docstring-tool
  "Creates the tool configuration for editing docstrings.
   Automatically inherits emacs notification preferences from the client."
  [nrepl-client-atom]
  (let [client @nrepl-client-atom
        emacs-notify (config/get-emacs-notify client)]
    {:tool-type :clojure-edit-replace-docstring
     :nrepl-client-atom nrepl-client-atom
     :enable-emacs-notifications emacs-notify}))

(defn create-edit-comment-block-tool
  "Creates the tool configuration for editing comment blocks.
   Automatically inherits emacs notification preferences from the client."
  [nrepl-client-atom]
  (let [client @nrepl-client-atom
        emacs-notify (config/get-emacs-notify client)]
    {:tool-type :clojure-edit-comment-block
     :nrepl-client-atom nrepl-client-atom
     :enable-emacs-notifications emacs-notify}))

;; Common validation functions

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

;; Implement the required multimethods for the form replacement tool

(defmethod tool-system/tool-name :clojure-edit-replace-form [_]
  "clojure_edit_replace_definition")

(defmethod tool-system/tool-description :clojure-edit-replace-form [_]
  "Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file by fully replacing it.
  
PREFER this tool along with `clojure_edit_insert_before_definition` ``clojure_edit_insert_after_definition` are for editing Clojure files (`.clj` `.cljs` `.cljc` `.bb`)

These tools MAKE it EASIER to match a definition that exists in the file AS you only have to match the type of definition `form_type` and the complete identifier `form_identifier` of the definition. This prevents the repeated mismatch errors that occur when trying match an entire `old_string` to replace it.
These tools validates the structure of the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.
These tools reduces the number of tokens that need to be generated and that makes me happy!
 
WARNING: you will receive errors if the syntax is wrong, the most common error is an extra or missing parenthesis at the end of the replacement function in `content`, so be careful with parenthesis.

This tool can replace a specific top-level form (like a function, def, or namespace declaration) with new content. The form is identified by its type (defn, def, deftest, s/def, ns, defmethod etc.) and complete identifier.
   
   Example: Replace the implementation of a `defn` named `example-fn`:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"example-fn\"
   - form_type: \"defn\"
   - content: \"(defn example-fn [x] (* x 2))\"
   
Note: For `defmethod` forms, be sure to include the dispatch value (`area :rectangle` or `qualified/area :rectangle`) in the `form_identifier`. Many `defmethod` definitions have qualified names (they include a namespace alias in their identifier like `shape/area`), so it's crucial to use the complete identifier that appears in the file. 

   Example: Replace the implementation of a `defmethod` named `shape/area :square`:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"shape/area :square\"
   - form_type: \"defmethod\"
   - content: \"(defmethod shape/area :square [{:keys [w h]}] (* w h))\"

   Example: Replace the implementation of a `defmethod` with a namespaced multimethod:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"tool-system/validate-inputs :clojure-eval\"
   - form_type: \"defmethod\"
   - content: \"(defmethod tool-system/validate-inputs :clojure-eval [_ inputs] ...)\"

   Example: Replace the implementation of a `defmethod` named `convert-length [:meters :inches]`:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"convert-length [:feet :inches]\"
   - form_type: \"defmethod\"
   - content: \"(defmethod convert-length [:feet :inches] [_ n]  (* 12 n))\"

   The tool will find the form, replace it with the new content.")

(defmethod tool-system/tool-schema :clojure-edit-replace-form [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Path to the file containing the form to edit"}
                :form_identifier {:type :string
                                  :description "Complete identifier of the form to edit (e.g., function identifiers like \"square\", \"shape/area\", or \"tool-system/validate-inputs :clojure-eval\")"}
                :form_type {:type :string
                            :description "Type of the form (e.g., \"defn\", \"def\", \"ns\")"}
                :content {:type :string
                          :description "New content to replace the form with"}}
   :required [:file_path :form_identifier :form_type :content]})

(defmethod tool-system/validate-inputs :clojure-edit-replace-form [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        ;; Accept form_identifier but map it to form_name internally for compatibility
        {:keys [form_identifier form_type content]} inputs
        form_name form_identifier]
    (when-not form_identifier
      (throw (ex-info "Missing required parameter: form_identifier"
                      {:inputs inputs})))
    (when-not form_type
      (throw (ex-info "Missing required parameter: form_type"
                      {:inputs inputs})))
    (when-not content
      (throw (ex-info "Missing required parameter: content"
                      {:inputs inputs})))
    ;; Return validated inputs with form_name for backward compatibility
    {:file_path file-path
     :form_name form_name
     :form_type form_type
     :content content}))

(defmethod tool-system/execute-tool :clojure-edit-replace-form [{:keys [nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path form_name form_type content]} inputs
        result (pipeline/edit-form-pipeline file_path form_name form_type content :replace tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

(defmethod tool-system/format-results :clojure-edit-replace-form [_ {:keys [error message diff]}]
  (if error
    {:result [message]
     :error true}
    {:result [diff]
     :error false}))

;; Implement the required multimethods for insert before form tool

(defmethod tool-system/tool-name :clojure-edit-insert-before-form [_]
  "clojure_edit_insert_before_definition")

(defmethod tool-system/tool-description :clojure-edit-insert-before-form [_]
  "Inserts content before a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file.
   
PREFER this tool along with `clojure_edit_replace_definition` and `clojure_edit_insert_after_definition` for editing Clojure files (`.clj` `.cljs` `.cljc` `.bb`)

These tools MAKE it EASIER to match a definition that exists in the file AS you only have to match the type of definition `form_type` and the complete identifier `form_identifier` of the definition. This prevents the repeated mismatch errors that occur when trying match an entire `old_string` to replace it.
These tools validates the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.
These tools reduces the number of tokens that need to be generated and that makes me happy!
 
WARNING: you will receive errors if the syntax is wrong, the most common error is an extra or missing parenthesis at the end of the inserted content in `content`, so be careful with parenthesis.

This tool adds new content before a specific top-level form (like a function, def, or namespace declaration) without modifying the form itself. The form is identified by its type (defn, def, deftest, s/def, ns, defmethod etc.) and complete identifier.
   
   Example: Insert a helper function before `example-fn`:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"example-fn\"
   - form_type: \"defn\"
   - content: \"(defn helper-fn [x] (* x 2))\"
   
Note: For `defmethod` forms, be sure to include the dispatch value (`area :rectangle` or `qualified/area :rectangle`) in the `form_identifier`. Many `defmethod` definitions have qualified names (they include a namespace alias in their identifier like `shape/area`), so it's crucial to use the complete identifier that appears in the file.

   Example: Insert a helper function before a `defmethod` named `shape/area :square`:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"shape/area :square\"
   - form_type: \"defmethod\"
   - content: \"(defn calculate-area [w h] (* w h))\"

   Example: Insert a helper function before a `defmethod` with a namespaced multimethod:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"tool-system/validate-inputs :clojure-eval\"
   - form_type: \"defmethod\"
   - content: \"(def validation-helpers {:required-keys #{:code}})\"

   Example: Insert a helper function before a `defmethod` with a vector dispatch value:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"convert-length [:feet :inches]\"
   - form_type: \"defmethod\"
   - content: \"(def inches-per-foot 12)\"

   The tool will find the form, insert the new content before it, and format the result.
   It returns a diff showing the changes made to the file.")

(defmethod tool-system/tool-schema :clojure-edit-insert-before-form [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Path to the file containing the form"}
                :form_identifier {:type :string
                                  :description "Complete identifier of the form (e.g., function name, \"shape/area\", or \"tool-system/validate-inputs :clojure-eval\")"}
                :form_type {:type :string
                            :description "Type of the form (e.g., \"defn\", \"def\", \"ns\")"}
                :content {:type :string
                          :description "Content to insert before the form"}}
   :required [:file_path :form_identifier :form_type :content]})

(defmethod tool-system/validate-inputs :clojure-edit-insert-before-form [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        ;; Accept form_identifier but map it to form_name internally for compatibility
        {:keys [form_identifier form_type content]} inputs
        form_name form_identifier]
    (when-not form_identifier
      (throw (ex-info "Missing required parameter: form_identifier"
                      {:inputs inputs})))
    (when-not form_type
      (throw (ex-info "Missing required parameter: form_type"
                      {:inputs inputs})))
    (when-not content
      (throw (ex-info "Missing required parameter: content"
                      {:inputs inputs})))
    ;; Return validated inputs with form_name for backward compatibility
    {:file_path file-path
     :form_name form_name
     :form_type form_type
     :content content}))

(defmethod tool-system/execute-tool :clojure-edit-insert-before-form [{:keys [nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path form_name form_type content]} inputs
        result (pipeline/edit-form-pipeline file_path form_name form_type content :before tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

(defmethod tool-system/format-results :clojure-edit-insert-before-form [_ {:keys [error message diff]}]
  (if error
    {:result [message]
     :error true}
    {:result [diff]
     :error false}))

;; Implement the required multimethods for insert after form tool

(defmethod tool-system/tool-name :clojure-edit-insert-after-form [_]
  "clojure_edit_insert_after_definition")

(defmethod tool-system/tool-description :clojure-edit-insert-after-form [_]
  "Inserts content after a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file.
   
PREFER this tool along with `clojure_edit_replace_definition` and `clojure_edit_insert_before_definition` for editing Clojure files (`.clj` `.cljs` `.cljc` `.bb`)

These tools MAKE it EASIER to match a definition that exists in the file AS you only have to match the type of definition `form_type` and the complete identifier `form_identifier` of the definition. This prevents the repeated mismatch errors that occur when trying match an entire `old_string` to replace it.
These tools validates the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.
These tools reduces the number of tokens that need to be generated and that makes me happy!
 
WARNING: you will receive errors if the syntax is wrong, the most common error is an extra or missing parenthesis at the end of the inserted content in `content`, so be careful with parenthesis.

This tool adds new content after a specific top-level form (like a function, def, or namespace declaration) without modifying the form itself. The form is identified by its type (defn, def, deftest, s/def, ns, defmethod etc.) and complete identifier.
   
   Example: Insert a test after a function named `example-fn`:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"example-fn\"
   - form_type: \"defn\"
   - content: \"(deftest example-fn-test\n  (is (= 4 (example-fn 2))))\"
   
Note: For `defmethod` forms, be sure to include the dispatch value (`area :rectangle` or `qualified/area :rectangle`) in the `form_identifier`. Many `defmethod` definitions have qualified names (they include a namespace alias in their identifier like `shape/area`), so it's crucial to use the complete identifier that appears in the file.

   Example: Insert a test after a `defmethod` named `shape/area :square`:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"shape/area :square\"
   - form_type: \"defmethod\"
   - content: \"(deftest square-area-test\n  (is (= 25 (:area (shape/area :square {:w 5 :h 5}))))\"

   Example: Insert a test after a `defmethod` with a namespaced multimethod:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"tool-system/validate-inputs :clojure-eval\"
   - form_type: \"defmethod\"
   - content: \"(deftest validate-clojure-eval-test\n  (is (map? (tool-system/validate-inputs nil {:code \"(+ 1 2)\"}))))\"

   Example: Insert a test after a `defmethod` with a vector dispatch value:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"convert-length [:feet :inches]\"
   - form_type: \"defmethod\"
   - content: \"(deftest feet-to-inches-test\n  (is (= 24 (convert-length [:feet :inches] 2))))\"

   The tool will find the form, insert the new content after it, and format the result.
   It returns a diff showing the changes made to the file.")

(defmethod tool-system/tool-schema :clojure-edit-insert-after-form [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Path to the file containing the form"}
                :form_identifier {:type :string
                                  :description "Complete identifier of the form (e.g., function name, \"shape/area\", or \"tool-system/validate-inputs :clojure-eval\")"}
                :form_type {:type :string
                            :description "Type of the form (e.g., \"defn\", \"def\", \"ns\")"}
                :content {:type :string
                          :description "Content to insert after the form"}}
   :required [:file_path :form_identifier :form_type :content]})

(defmethod tool-system/validate-inputs :clojure-edit-insert-after-form [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        ;; Accept form_identifier but map it to form_name internally for compatibility
        {:keys [form_identifier form_type content]} inputs
        form_name form_identifier]
    (when-not form_identifier
      (throw (ex-info "Missing required parameter: form_identifier"
                      {:inputs inputs})))
    (when-not form_type
      (throw (ex-info "Missing required parameter: form_type"
                      {:inputs inputs})))
    (when-not content
      (throw (ex-info "Missing required parameter: content"
                      {:inputs inputs})))
    ;; Return validated inputs with form_name for backward compatibility
    {:file_path file-path
     :form_name form_name
     :form_type form_type
     :content content}))

(defmethod tool-system/execute-tool :clojure-edit-insert-after-form [{:keys [nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path form_name form_type content]} inputs
        result (pipeline/edit-form-pipeline file_path form_name form_type content :after tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

(defmethod tool-system/format-results :clojure-edit-insert-after-form [_ {:keys [error message diff]}]
  (if error
    {:result [message]
     :error true}
    {:result [diff]
     :error false}))

;; Implement the required multimethods for docstring edit tool

(defmethod tool-system/tool-name :clojure-edit-replace-docstring [_]
  "clojure_edit_replace_docstring")

(defmethod tool-system/tool-description :clojure-edit-replace-docstring [_]
  "Edits only the docstring of a top-level form in a Clojure file.
   
   This tool updates the docstring of a function, def, or other form without
   modifying the rest of the form. The form is identified by its type and complete identifier.
   
   Example: Update the docstring of 'example-fn':
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"example-fn\"
   - form_type: \"defn\"
   - docstring: \"Takes an integer and doubles it.\"
   
   Example: Update the docstring of a namespaced multimethod implementation:
   - file_path: \"/path/to/file.clj\"
   - form_identifier: \"tool-system/validate-inputs :clojure-eval\"
   - form_type: \"defmethod\"
   - docstring: \"Validates inputs for the clojure-eval tool.\"
   
   The tool will find the form, update only its docstring, and format the result.
   It returns the updated file content, the location offsets, and a diff of the changes.")

(defmethod tool-system/tool-schema :clojure-edit-replace-docstring [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Path to the file containing the form"}
                :form_identifier {:type :string
                                  :description "Complete identifier of the form (e.g., function name, \"shape/area\", or \"tool-system/validate-inputs :clojure-eval\")"}
                :form_type {:type :string
                            :description "Type of the form (e.g., \"defn\", \"def\")"}
                :docstring {:type :string
                            :description "New docstring content"}}
   :required [:file_path :form_identifier :form_type :docstring]})

(defmethod tool-system/validate-inputs :clojure-edit-replace-docstring [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        ;; Accept form_identifier but map it to form_name internally for compatibility
        {:keys [form_identifier form_type docstring]} inputs
        form_name form_identifier]
    (when-not form_identifier
      (throw (ex-info "Missing required parameter: form_identifier"
                      {:inputs inputs})))
    (when-not form_type
      (throw (ex-info "Missing required parameter: form_type"
                      {:inputs inputs})))
    (when-not docstring
      (throw (ex-info "Missing required parameter: docstring"
                      {:inputs inputs})))
    ;; Return validated inputs with form_name for backward compatibility
    {:file_path file-path
     :form_name form_name
     :form_type form_type
     :docstring docstring}))

(defmethod tool-system/execute-tool :clojure-edit-replace-docstring
  [{:keys [nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path form_name form_type docstring]} inputs
        result (pipeline/docstring-edit-pipeline file_path form_name form_type docstring tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

(defmethod tool-system/format-results :clojure-edit-replace-docstring [_ {:keys [error message diff]}]
  (if error
    {:result [message]
     :error true}
    {:result [diff]
     :error false}))

;; Implement the required multimethods for comment block edit tool

(defmethod tool-system/tool-name :clojure-edit-comment-block [_]
  "clojure_edit_replace_comment_block")

(defmethod tool-system/tool-description :clojure-edit-comment-block [_]
  "Replaces an entire comment block in a Clojure file by finding the first block containing the specified substring.

For line comments (;;): A 'block' is a contiguous sequence of comment lines with no blank lines or code between them.

For comment forms: A 'block' is the entire (comment ...) form including all of its contents. When editing a comment form, you must include the (comment ...) wrapper in your new content.

The function searches for the first occurrence of the comment_substring in comment blocks and replaces the entire containing block with the new content. It cannot make partial edits within a block.

For reliable results, use a unique substring that appears in only one comment block.")

(defmethod tool-system/tool-schema :clojure-edit-comment-block [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Path to the file containing the comment"}
                :comment_substring {:type :string
                                    :description "Substring to identify the comment block"}
                :new_content {:type :string
                              :description "The replacement comment block"}}
   :required [:file_path :comment_substring :new_content]})

(defmethod tool-system/validate-inputs :clojure-edit-comment-block [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        {:keys [comment_substring new_content]} inputs]
    (when-not comment_substring
      (throw (ex-info "Missing required parameter: comment_substring"
                      {:inputs inputs})))
    (when-not new_content
      (throw (ex-info "Missing required parameter: new_content"
                      {:inputs inputs})))
    ;; Return validated inputs
    {:file_path file-path
     :comment_substring comment_substring
     :new_content new_content}))

(defmethod tool-system/execute-tool :clojure-edit-comment-block [{:keys [nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path comment_substring new_content]} inputs
        result (pipeline/comment-block-edit-pipeline file_path comment_substring new_content tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

(defmethod tool-system/format-results :clojure-edit-comment-block [_ {:keys [error message diff]}]
  (if error
    {:result [message]
     :error true}
    {:result [diff]
     :error false}))

;; Backward compatibility functions

(defn top-level-form-edit-tool [nrepl-client-atom]
  (tool-system/registration-map (create-edit-replace-form-tool nrepl-client-atom)))

(defn top-level-form-insert-before-tool [nrepl-client-atom]
  (tool-system/registration-map (create-edit-insert-before-form-tool nrepl-client-atom)))

(defn top-level-form-insert-after-tool [nrepl-client-atom]
  (tool-system/registration-map (create-edit-insert-after-form-tool nrepl-client-atom)))

(defn docstring-edit-tool [nrepl-client-atom]
  (tool-system/registration-map (create-edit-docstring-tool nrepl-client-atom)))

(defn comment-block-edit-tool [nrepl-client-atom]
  (tool-system/registration-map (create-edit-comment-block-tool nrepl-client-atom)))

(defn create-edit-replace-sexp-tool
  "Creates the tool configuration for replacing s-expressions in a file.
   Automatically inherits emacs notification preferences from the client."
  [nrepl-client-atom]
  (let [client @nrepl-client-atom
        emacs-notify (config/get-emacs-notify client)]
    {:tool-type :clojure-edit-replace-sexp
     :nrepl-client-atom nrepl-client-atom
     :enable-emacs-notifications emacs-notify}))

(defmethod tool-system/tool-name :clojure-edit-replace-sexp [_]
  "clojure_edit_replace_sexp")

(defmethod tool-system/tool-description :clojure-edit-replace-sexp [_]
  "Edits a file by finding and replacing specific s-expressions.

Use this tool for targeted edits of sub-expressions within forms. For complete top-level form replacements, prefer specialized tools like `clojure_edit_replace_definition` and friends.

KEY BENEFITS:
- Syntax-aware matching that understands Clojure code structure
- Ignores whitespace differences by default, focusing on actual code meaning
- Matches expressions regardless of formatting, indentation, or spacing
- Prevents errors from mismatched text or irrelevant formatting differences
- Can find and replace all occurrences with replace_all: true

CONSTRAINTS:
- match_form must contain only a SINGLE s-expression (error otherwise)
- Requires syntactically valid Clojure expressions in both match_form and new_form

WARNING: You will receive errors if the syntax is wrong. The most common error is an extra or missing parenthesis in either match_form or new_form, so count your parentheses carefully!

COMMON APPLICATIONS:
- Renaming symbols throughout the file: {\"match_form\": \"old-name\", \"new_form\": \"new-name\", \"replace_all\": true}
- Editing special forms like if/cond/let/when within functions:
  * Changing if branches: {\"match_form\": \"(if condition expr1 expr2)\", \"new_form\": \"(if condition (do expr1) expr2)\"}
  * Enhancing let bindings: {\"match_form\": \"(let [x 10] (+ x 2))\", \"new_form\": \"(let [x 10 y 20] (+ x y))\"}
  * Converting cond to case: {\"match_form\": \"(cond (= x :a) \\\"A\\\" (= x :b) \\\"B\\\")\", \"new_form\": \"(case x :a \\\"A\\\" :b \\\"B\\\")\"}
- Modifying nested function calls: {\"match_form\": \"(str (+ a b))\", \"new_form\": \"(str (+ a b c))\"}
- Changing anonymous function syntax: {\"match_form\": \"#(update % :count inc)\", \"new_form\": \"(fn [x] (update x :count inc))\"}

Other Examples:
- Replace a calculation: {\"match_form\": \"(+ x 2)\", \"new_form\": \"(+ x 10)\"}
  * Will match regardless of spaces: (+ x 2), (+   x   2), etc.
- Delete an expression: {\"match_form\": \"(println debug-info)\", \"new_form\": \"\"}
- Edit anonymous function: {\"match_form\": \"#(inc %)\", \"new_form\": \"#(+ % 2)\"}
- Replace multiple occurrences: {\"match_form\": \"(inc x)\", \"new_form\": \"(+ x 1)\", \"replace_all\": true}

Returns a diff showing the changes made to the file.")

(defmethod tool-system/tool-schema :clojure-edit-replace-sexp [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Path to the file to edit"}
                :match_form {:type :string
                             :description "The s-expression to find and replace (include # for anonymous functions)"}
                :new_form {:type :string
                           :description "The s-expression to replace with"}
                :replace_all {:type :boolean
                              :description "Whether to replace all occurrences (default: false)"}}
   :required [:file_path :match_form :new_form]})

(defmethod tool-system/validate-inputs :clojure-edit-replace-sexp [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        {:keys [match_form new_form replace_all whitespace_sensitive]} inputs]
    (when-not match_form
      (throw (ex-info "Missing required parameter: match_form"
                      {:inputs inputs})))
    (when-not new_form
      (throw (ex-info "Missing required parameter: new_form"
                      {:inputs inputs})))

    (when (str/blank? match_form)
      (throw (ex-info "Bad parameter: match-form can not be a blank string."
                      {:inputs inputs})))
    ;; Special handling for empty string
    (when-not (str/blank? match_form)
      (try
        (let [parsed (p/parse-string-all match_form)]
          ;; Check if there's at least one non-whitespace, non-comment node
          (when (zero? (count (n/child-sexprs parsed)))
            (throw (ex-info "match_form must contain at least one S-expression (not just comments or whitespace)"
                            {:inputs inputs}))))
        (catch Exception e
          (if (str/includes? (.getMessage e) "match_form must contain")
            (throw e)
            (throw (ex-info (str "Invalid Clojure code in match_form: " (.getMessage e))
                            {:inputs inputs}))))))

    (when-not (str/blank? new_form)
      ;; Validate that new_form is valid Clojure code
      (try
        (p/parse-string-all new_form)
        (catch Exception e
          (throw (ex-info (str "Invalid Clojure code in new_form: " (.getMessage e))
                          {:inputs inputs})))))
    ;; Return validated inputs
    {:file_path file-path
     :match_form match_form
     :new_form new_form
     :replace_all (boolean (or replace_all false))
     :whitespace_sensitive (boolean (or whitespace_sensitive false))}))

(defmethod tool-system/execute-tool :clojure-edit-replace-sexp [{:keys [nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path match_form new_form replace_all whitespace_sensitive]} inputs
        result (pipeline/sexp-replace-pipeline
                file_path match_form new_form replace_all whitespace_sensitive tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

(defmethod tool-system/format-results :clojure-edit-replace-sexp [_ {:keys [error message diff]}]
  (if error
    {:result [message]
     :error true}
    {:result [diff]
     :error false}))

;; Function to register the tool
(defn sexp-replace-tool [nrepl-client-atom]
  (tool-system/registration-map (create-edit-replace-sexp-tool nrepl-client-atom)))

(comment
  ;; === Examples of using the form editing tools ===
  (require 'clojure-mcp.nrepl)
  ;; Setup for REPL-based testing
  (def client-atom (atom (assoc
                          (clojure-mcp.nrepl/create {:port 7888})
                          :clojure-mcp.core/nrepl-user-dir (System/getProperty "user.dir"))))
  (clojure-mcp.nrepl/start-polling @client-atom)

  ;; Create tool instances
  (def replace-tool (create-edit-replace-form-tool client-atom))
  (def insert-before-tool (create-edit-insert-before-form-tool client-atom))
  (def insert-after-tool (create-edit-insert-after-form-tool client-atom))
  (def docstring-tool (create-edit-docstring-tool client-atom))
  (def comment-tool (create-edit-comment-block-tool client-atom))
  (def outline-tool (create-file-structure-tool client-atom))
  (def sexp-tool (create-edit-replace-sexp-tool client-atom))

  ;; Test the replace form tool
  (def replace-inputs
    {:file_path "tmp/test.clj"
     :form_name "power"
     :form_type "defn"
     :content "(defn power [x]\n  (* x 55))"})
  (def replace-validated (tool-system/validate-inputs replace-tool replace-inputs))
  (def replace-result (tool-system/execute-tool replace-tool replace-validated))
  (def replace-formatted (tool-system/format-results replace-tool replace-result))

  ;; Test the comment edit tool
  (def comment-inputs
    {:file_path "/tmp/test.clj"
     :comment_substring "TODO"
     :new_content ";; DONE: Implemented feature"})
  (def comment-validated (tool-system/validate-inputs comment-tool comment-inputs))
  (def comment-result (tool-system/execute-tool comment-tool comment-validated))
  (def comment-formatted (tool-system/format-results comment-tool comment-result))

  ;; Test the sexp replace tool
  (def sexp-inputs
    {:file_path "/tmp/test.clj"
     :match_form "(+ 1 2)"
     :new_form "(+ 1 3)"
     :replace_all true})
  (def sexp-validated (tool-system/validate-inputs sexp-tool sexp-inputs))
  (def sexp-result (tool-system/execute-tool sexp-tool sexp-validated))
  (def sexp-formatted (tool-system/format-results sexp-tool sexp-result))

  ;; Test with anonymous functions
  (def anon-fn-inputs
    {:file_path "/tmp/test.clj"
     :match_form "#(+ x 2)"
     :new_form "#(+ x 5)"
     :replace_all true})
  (def anon-fn-validated (tool-system/validate-inputs sexp-tool anon-fn-inputs))
  (def anon-fn-result (tool-system/execute-tool sexp-tool anon-fn-validated))
  (def anon-fn-formatted (tool-system/format-results sexp-tool anon-fn-result))

  ;; Test with empty strings
  (def empty-str-inputs
    {:file_path "/tmp/test.clj"
     :match_form ""
     :new_form "(comment \"This replaces empty nodes\")"
     :replace_all true})
  (def empty-str-validated (tool-system/validate-inputs sexp-tool empty-str-inputs))
  (def empty-str-result (tool-system/execute-tool sexp-tool empty-str-validated))
  (def empty-str-formatted (tool-system/format-results sexp-tool empty-str-result))

  ;; Make a simpler test function
  (defn test-tool [tool inputs]
    (let [prom (promise)
          reg-map (tool-system/registration-map tool)
          tool-fn (:tool-fn reg-map)]
      (tool-fn nil inputs
               (fn [result error]
                 (deliver prom (if error {:error error} {:result result}))))
      @prom))

  ;; Test various tools
  (test-tool replace-tool replace-inputs)
  (test-tool comment-tool comment-inputs)
  (test-tool outline-tool {:file_path "/tmp/test.clj"})
  (test-tool sexp-tool sexp-inputs)
  (test-tool sexp-tool anon-fn-inputs)
  (test-tool sexp-tool empty-str-inputs)

  ;; Clean up
  (clojure-mcp.nrepl/stop-polling @client-atom))
