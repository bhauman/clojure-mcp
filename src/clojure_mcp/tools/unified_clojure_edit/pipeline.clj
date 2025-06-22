(ns clojure-mcp.tools.unified-clojure-edit.pipeline
  "Pipeline architecture for pattern-based Clojure code editing operations.
   Provides a thread-first pattern with error short-circuiting and
   standardized context maps."
  (:require
   [clojure-mcp.tools.unified-clojure-edit.core :as core]
   [clojure-mcp.tools.form-edit.pipeline :as form-edit-pipeline]
   [clojure-mcp.utils.emacs-integration :as emacs]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [rewrite-clj.zip :as z]
   [rewrite-clj.parser :as p]
   [clojure-mcp.linting :as linting]
   [clojure-mcp.sexp.paren-utils :as paren-utils]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.java.io :as io]))

;; Additional custom spec for the pattern string
(s/def ::pattern string?)

(defn find-form
  "Finds a form using pattern matching.
   Requires ::zloc and ::pattern in the context.
   Updates ::zloc to point to the matched form or returns an error context if no match found."
  [ctx]
  (let [zloc (::form-edit-pipeline/zloc ctx)
        pattern (::pattern ctx)
        result (core/find-pattern-match zloc pattern)]
    (if (:zloc result)
      (assoc ctx ::form-edit-pipeline/zloc (:zloc result))
      {::form-edit-pipeline/error true
       ::form-edit-pipeline/message (str "Could not find pattern match for: " pattern
                                         " in file " (::form-edit-pipeline/file-path ctx))})))

(defn check-for-duplicate-matches
  "Checks if there are multiple matches for the pattern.
   Requires ::zloc and ::pattern in the context.
   Returns an error context if multiple matches are found."
  [ctx]
  (let [zloc (::form-edit-pipeline/zloc ctx)
        pattern (::pattern ctx)
        ;; Start from the next position after the current match
        next-zloc (z/next zloc)
        ;; Use the same function that was used for the first match
        second-match (core/find-pattern-match next-zloc pattern)]
    (if (:zloc second-match)
      ;; Found a second match - this is an error
      {::form-edit-pipeline/error true
       ::form-edit-pipeline/message
       (str "Multiple matches found for pattern: " pattern
            "\nFirst match: " (z/string zloc)
            "\nSecond match: " (z/string (:zloc second-match))
            "\nPlease use a more specific pattern to ensure a unique match.")}
      ;; No second match found - this is good
      ctx)))

(defn edit-form
  "Edits the form according to the specified edit type.
   Requires ::zloc, ::pattern, ::new-source-code, and ::edit-type in the context.
   Updates ::zloc with the edited zipper."
  [ctx]
  (let [zloc (::form-edit-pipeline/zloc ctx)
        pattern (::pattern ctx)
        content (::form-edit-pipeline/new-source-code ctx)
        edit-type (::form-edit-pipeline/edit-type ctx)
        result (core/edit-matched-form zloc pattern content edit-type)]
    (if (:zloc result)
      (assoc ctx ::form-edit-pipeline/zloc (:zloc result))
      {::form-edit-pipeline/error true
       ::form-edit-pipeline/message (str "Failed to " (name edit-type) " form matching pattern: " pattern)})))

;; Define the main pipeline for pattern-based Clojure editing
(defn pattern-edit-pipeline
  "Pipeline for handling pattern-based Clojure code editing operations.
   
   Arguments:
   - file-path: Path to the file to edit
   - pattern: Pattern string to match (with ? and * wildcards)
   - content-str: New content to insert
   - edit-type: Type of edit (:replace, :insert_before, :insert_after)
   - nrepl-client-atom: Atom containing the nREPL client (optional)
   - config: Optional tool configuration map
   
   Returns a context map with the result of the operation"
  [file-path pattern content-str edit-type {:keys [nrepl-client-atom] :as config}]
  (let [ctx {::form-edit-pipeline/file-path file-path
             ::pattern pattern
             ::form-edit-pipeline/new-source-code content-str
             ::form-edit-pipeline/edit-type edit-type
             ::form-edit-pipeline/nrepl-client-atom nrepl-client-atom
             ::form-edit-pipeline/config config}]
    (form-edit-pipeline/thread-ctx
     ctx
     form-edit-pipeline/lint-repair-code
     form-edit-pipeline/load-source
     form-edit-pipeline/check-file-modified
     form-edit-pipeline/parse-source
     find-form
     check-for-duplicate-matches
     edit-form
     form-edit-pipeline/capture-edit-offsets
     form-edit-pipeline/zloc->output-source
     form-edit-pipeline/format-source
     form-edit-pipeline/determine-file-type
     form-edit-pipeline/generate-diff
     form-edit-pipeline/save-file
     form-edit-pipeline/update-file-timestamp
     form-edit-pipeline/highlight-form)))
