(ns clojure-mcp.tools.unified-clojure-edit.tool
  "MCP tool implementation for the unified Clojure edit operation.
   Provides a pattern-based approach to finding and editing Clojure code."
  (:require [clojure-mcp.tool-system :as tool-system]
            [clojure-mcp.tools.unified-clojure-edit.pipeline :as clj-edit-pipeline]
            [clojure-mcp.tools.form-edit.pipeline :as pipeline]
            [clojure-mcp.delimiter :as delimiter]
            [clojure-mcp.utils.valid-paths :as valid-paths]
            [taoensso.timbre :as log]
            [clojure.string :as str]))

;; Tool name and description
(defmethod tool-system/tool-name :clojure-pattern-edit [_]
  "clojure_edit")

(defmethod tool-system/tool-description :clojure-pattern-edit [_]
  "Clojure code editing tool that uses simple wildcards to match and edit code.

This tool provides a powerful efficient approach to edit Clojure code using pattern matching instead of having to specify exact textual matches. The pattern syntax supports two wildcards:
   
   - `_?` matches exactly one Clojure form (a single s-expression)
   - `_*` matches zero or more Clojure forms (multiple s-expressions)
   
IMPORTANT: These wildcards match CLOJURE FORMS ONLY, not arbitrary text:
   - A form is a single syntactic unit in Clojure: symbol, keyword, list, vector, map, etc.
   - `_?` can match: a symbol (x), a keyword (:key), a list ((+ 1 2)), a vector ([1 2 3]), etc.
   - `_?` CANNOT match partial forms or arbitrary strings of text
   - These patterns work on the parsed syntax tree, not on raw text
   - Example: To match `(+ 1 2)`, `_?` would match the entire expression, not just `+` or `1`

The pattern matches the sexpr of the code ignoring whitespace.

This tool has three operations
  - \"replace\" replaces the matched sexpr with the new content  
  - \"insert_after\" inserts the new content after the matched sexpr
  - \"insert_before\" inserts the new content before the matched sexpr

PREFER this tool for editing Clojure files (`.clj` `.cljs` `.cljc` `.bb`)

These tools MAKE it EASIER to match a definition that exists in the file AS you only have to match the shape of a definition `(defn hello _*)`. This prevents the repeated mismatch errors that occur when trying match an entire `old_string` to replace it.

These tools validates the structure of the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.

This tool reduces the number of tokens that need to be generated and that makes me happy!

IMPORTANT: Pattern matching ignores comments and metadata
- When matching patterns, the tool converts code to s-expressions which COMPLETELY IGNORE comments and metadata
- You CANNOT match based on comments or specific metadata in your patterns
- Line comments (starting with ;, ;;, etc.) in the match pattern are ignored during pattern matching
- However, any comments or metadata in your replacement CONTENT will be preserved
- For editing focused specifically on comments or metadata, consider using `file_edit` instead

WARNING: you will receive errors if the syntax is wrong, the most common error is an extra or missing parenthesis at the end of the replacement function in `content`, so be careful with parenthesis.

IMPORTANT: use the SMALLEST expression that UNIQUELY matches the code you want to match.

The following are the MOST common patterns you will use:

This tool will mostly be used to operation on top level forms (defn, def, deftest, s/def, ns, defmethod etc.) with new content. The top level form is easily matched with a simple pattern that includes the identifiers for the form like `(defmethod shape/area :rectangle _*)` where the `_*` matches the rest of the forms in the definition. 
   
   Example: Replace the implementation of a `defn` named `example-fn`:
   - file_path: \"/path/to/file.clj\"
   - sexp_pattern: \"(defn example-fn _*)\"
   - raw_content: \"(defn example-fn [x] (+ x 2))\"
   - operation: \"replace\"
   
Note: For `defmethod` forms, be sure to include the dispatch value (`area :rectangle` or `qualified/area :rectangle`) in the `form_identifier`. Many `defmethod` definitions have qualified names (they include a namespace alias in their identifier like `shape/area`), so it's crucial to use the complete identifier that appears in the file. 

   Example: Replace a namespace:
   - file_path: \"/path/to/file.clj\"
   - sexp_pattern: \"(ns my-cool-proj.core _*)\"
   - raw_content: \"(ns my-cool-proj.core\n (:requires [clojure.string :as string]))\"
   - operation: \"replace\"

   Example: Replace the implementation of a `defmethod` named `shape/area :square`:
   - file_path: \"/path/to/file.clj\"
   - sexp_pattern: \"(defmethod shape/area :square _*)\"
   - raw_content: \"(defmethod shape/area :square [{:keys [w h]}] (* w h))\"
   - operation: \"replace\"

   Example: Replace the implementation of a `defmethod` with a namespaced multimethod:
   - file_path: \"/path/to/file.clj\"
   - sexp_pattern: \"(defmethod tool-system/validate-inputs :clojure-eval _*)\"
   - raw_content: \"(defmethod tool-system/validate-inputs :clojure-eval [_ inputs] ...)\"
   - operation: \"replace\"

   Example: Insert a new defmethod after the implementation of a `defmethod` named `convert-length [:meters :inches]`:
   - file_path: \"/path/to/file.clj\"
   - sexp_pattern: \"(defmethod convert-length [:feet :inches] _*)\"
   - raw_content: \"(defmethod convert-length [:feet :yard] [_ n] (/ n 3))\"
   - operation: \"insert_after\"

You can also edit sublevel forms

   Example: Edit a namespace changing an alias:
   - file_path: \"/path/to/file.clj\"
   - sexp_pattern: \"[clojure.string :as _?]\"
   - raw_content: \"[clojure.string :as str]\"
   - operation: \"replace\"

   Example: Edit a namespace adding a library:
   - file_path: \"/path/to/file.clj\"
   - sexp_pattern: \"[clojure.string :as _?]\"
   - raw_content: \"[clojure.java.io :as io]\n  [clojure.set :as set]  \n\"
   - operation: \"insert_after\"

THis tool can also target explicit sexps when used without the pattern symbols.


   This approach is more flexible than traditional form-edit tools as it doesn't require perfect text matches for the edit to succeed, making it easier to target specific code structures with simpler patterns.")

;; Tool schema
(defmethod tool-system/tool-schema :clojure-pattern-edit [_]
  {:type :object
   :properties
   {:file_path
    {:type :string
     :description "Path to the file to edit"}

    :sexp_pattern
    {:type :string
     :description "Sexp pattern to match using _? (single form) and _* (multiple forms) wildcards. Must be a valid Clojure sexpr"}

    :raw_content
    {:type :string
     :description "New content to replace or insert"}

    :operation
    {:enum ["replace" "insert_before" "insert_after"]
     :description "Edit operation to perform"}}

   :required ["file_path" "sexp_pattern" "raw_content" "operation"]})

;; Validate inputs
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

(defmethod tool-system/validate-inputs :clojure-pattern-edit [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        {:keys [sexp_pattern raw_content operation]} inputs]
    (when-not sexp_pattern
      (throw (ex-info "Missing required parameter: sexp_pattern"
                      {:inputs inputs})))

    ;; checking that its a sexp
    (when (delimiter/delimiter-error? sexp_pattern)
      (throw
       (ex-info "Must be a valid Sexpr: sexp_pattern has delimiter errors (unbalanced parentheses, brackets, or braces)"
                {:sexp_pattern sexp_pattern})))

    ;; check that its not a comment
    (let [form-count (delimiter/count-forms sexp_pattern)]
      (when (and (str/starts-with? (str/trim sexp_pattern) ";")
                 (zero? form-count))
        (throw
         (ex-info (str "Must be a valid Sexpr: sexp_pattern.n"
                       "sexpr_pattern can not match line comments.\n"
                       "to edit line comments use `file_edit`")
                  {:sexp_pattern sexp_pattern})))

      (when-not (= 1 form-count)
        (throw
         (ex-info (str "Must be a single Sexpr: sexp_pattern.n\n "
                       "The pattern provided contains mutliple expressions.")
                  {:sexp_pattern sexp_pattern}))))

    (when (#{"_*" "_?"} (str/trim sexp_pattern))
      (throw
       (ex-info (str "Match pattern to general to be useful: sexp_pattern.n"
                     "try a more specifc pattern.\n")
                {:sexp_pattern sexp_pattern})))

    (when-not raw_content
      (throw (ex-info "Missing required parameter: raw_content"
                      {:inputs inputs})))

    (when-not (contains? #{"replace" "insert_before" "insert_after"} operation)
      (throw (ex-info "Operation must be one of: replace, insert_before, insert_after"
                      {:inputs inputs
                       :operation operation})))

    ;; Return validated inputs
    {:file_path file-path
     :pattern sexp_pattern
     :content raw_content
     :operation (keyword operation)}))

;; Execute the tool
(defmethod tool-system/execute-tool :clojure-pattern-edit [{:keys [_nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path pattern content operation]} inputs]

    (log/info "Executing clojure_pattern_edit"
              {:file file_path
               :pattern pattern
               :operation operation})
        ;formatted-result (cljpipeline/format-result result)
    (pipeline/format-result
     (clj-edit-pipeline/pattern-edit-pipeline
      file_path
      pattern
      content
      operation
      tool))))

;; Format the results for output
(defmethod tool-system/format-results :clojure-pattern-edit [_ {:keys [error message diff]}]
  (if error
    {:result [message]
     :error true}
    {:result [diff]
     :error false}))

(defn clojure-pattern-edit-tool
  "Factory function that creates a tool for pattern-based Clojure editing.
   
   Arguments:
   - nrepl-client-atom: Atom containing the nREPL client
   
   Returns:
   - A map specifying the tool for registration with MCP"
  [nrepl-client-atom]
  {:tool-type :clojure-pattern-edit
   :nrepl-client-atom nrepl-client-atom})

;; Function to register the tool
(defn clojure-edit-tool [nrepl-client-atom]
  (tool-system/registration-map (clojure-pattern-edit-tool nrepl-client-atom)))

