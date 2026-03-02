(ns clojure-mcp.tools.form-edit.pipeline
  "Pipeline architecture for form editing operations.
   Provides a thread-first pattern with error short-circuiting and
   standardized context maps."
  (:require
   [clojure-mcp.tools.form-edit.core :as core]
   [clojure-mcp.tools.agent-tool-builder.file-changes :as file-changes]
   [clojure-mcp.utils.diff :as diff-utils]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure-mcp.config :as config]
   [rewrite-clj.zip :as z]
   [clojure-mcp.delimiter :as delimiter]
   [clojure-mcp.sexp.paren-utils :as paren-utils]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.java.io :as io]))

;; Context map specs

(s/def ::file-path string?)
(s/def ::source string?)
(s/def ::old-content string?)
(s/def ::new-source-code string?)
(s/def ::output-source string?)
(s/def ::top-level-def-name string?)
(s/def ::top-level-def-type string?)
(s/def ::edit-type #{:replace :before :after})
(s/def ::error boolean?)
(s/def ::message string?)
(s/def ::zloc any?) ;; zloc is opaque, so we'll use any?
(s/def ::offsets (s/or :tuple (s/tuple int? int?)
                       :vector (s/coll-of int?)))
(s/def ::lint-result (s/nilable map?))
(s/def ::docstring string?)
(s/def ::comment-substring string?)
(s/def ::new-content string?)

(s/def ::diff string?)
(s/def ::type string?)

(s/def ::nrepl-client-atom (s/nilable #(instance? clojure.lang.Atom %)))

(s/def ::dry-run (s/nilable #{"diff" "new-source"}))

(s/def ::form-col pos-int?)

(s/def ::context
  (s/keys :req [::file-path]
          :opt [::source ::old-content ::new-source-code ::top-level-def-name
                ::top-level-def-type ::edit-type ::error ::message
                ::zloc ::offsets ::lint-result ::docstring
                ::comment-substring ::new-content ::expand-symbols
                ::diff ::type ::output-source ::nrepl-client-atom ::dry-run
                ::form-col]))

;; Pipeline helper functions

(defn thread-ctx
  "Thread a context map through a series of functions, short-circuiting on error.
   
   Arguments:
   - ctx: The initial context map
   - fns: A sequence of functions to apply to the context
   
   Returns:
   - The final context, possibly containing an error"
  [ctx & fns]
  (reduce (fn [c f]
            (if (::error c)
              (reduced c) ; Short-circuit on error using reduced
              (f c)))
          ctx
          fns))

(defn file-path->lang
  "Extract language type from file path extension.
   Returns :clj if file-path is nil."
  [file-path]
  (if file-path
    (cond
      (.endsWith file-path ".cljc") :cljc
      (.endsWith file-path ".cljs") :cljs
      :else :clj)
    :clj))
;; Common pipeline steps

(defn load-source
  "Loads the content of a file into the context map.
   Adds ::source to the context."
  [ctx]
  (let [result (core/load-file-content (::file-path ctx))]
    (if (:error result)
      {::error true
       ::message (:message result)}
      (-> ctx
          (assoc ::source (:content result))
          (assoc ::old-content (:content result))))))

(defn check-file-modified
  "Checks if the file has been modified since last read.
   Returns error if modified without being read again.
   Respects the write-file-guard configuration setting.
   
   Requires ::file-path and ::nrepl-client-atom in context."
  [ctx]
  (let [file-path (::file-path ctx)
        nrepl-client-atom (::nrepl-client-atom ctx)
        ;; Check if write guard is enabled
        nrepl-client (when nrepl-client-atom @nrepl-client-atom)]
    ;; If write guard is disabled, skip all checks
    (if (and nrepl-client (not (config/write-guard? nrepl-client)))
      ctx
      ;; Otherwise perform the normal checks
      (if (or (::file-modifed ctx)
              (and nrepl-client-atom
                   (file-timestamps/file-modified-since-read? nrepl-client-atom file-path)))
        {::error true
         ::message (str "File has been modified since last read: " file-path
                        "\nPlease read the file again before editing.")}
        ctx))))

(defn lint-repair-code
  "Checks for delimiter errors in the source code and attempts to fix them.
   If repair is successful, updates the source code in the context.
   Adds potentially ::repaired to the context."
  ([ctx]
   (lint-repair-code ctx ::new-source-code))
  ([ctx ky]
   (let [original-code (get ctx ky)]
     (if (delimiter/delimiter-error? original-code)
       (if-let [repaired-code (paren-utils/parinfer-repair original-code)]
         (-> ctx
             (assoc ky repaired-code)
             (assoc ::repaired true)
             (assoc ::original-code original-code))
         {::error :lint-failure
          ::message "Delimiter errors detected in Clojure code and automatic repair failed.\nPlease fix the syntax errors before saving."})
       ;; No delimiter errors, return context unchanged
       ctx))))

(defn enhance-defmethod-name
  "If this is a defmethod form without a dispatch value in its name,
   extract the dispatch value from the replacement code.
   
   This function handles the special case of defmethod forms by extracting
   the dispatch value from the replacement content when not explicitly provided
   in the form name. This allows users to target specific defmethod implementations
   without having to use the 'method-name dispatch-value' syntax.
   
   Requires ::top-level-def-type, ::top-level-def-name, and ::new-source-code in the context.
   May update ::top-level-def-name with the enhanced name."
  [ctx]
  (let [def-type (get ctx ::top-level-def-type)
        def-name (get ctx ::top-level-def-name)
        new-source (get ctx ::new-source-code)]

    (if (= def-type "defmethod")
      (let [name-parts (str/split def-name #"\s+")]
        (if (= (count name-parts) 1)
          ;; No dispatch value in name - try to extract from content
          (if-let [[_ dispatch-str] (core/extract-dispatch-from-defmethod new-source)]
            ;; Successfully extracted dispatch - update name
            (assoc ctx ::top-level-def-name (str def-name " " dispatch-str))
            ;; Couldn't extract - keep original name
            ctx)
          ;; Already has dispatch value - keep original name
          ctx))
      ;; Not a defmethod - keep original name
      ctx)))

(defn parse-source
  "Parses the source string into a zipper location.
   Adds ::zloc to the context."
  [ctx]
  (try
    (let [zloc (z/of-string (::source ctx) {:track-position? true})]
      (assoc ctx ::zloc zloc))
    (catch Exception e
      {::error true
       ::message (str "Error parsing source: " (.getMessage e))})))

(defn validate-form-type
  "Validates that the form type is supported for the operation.
   Returns the context unchanged if valid, or error context if invalid.
   
   Arguments:
   - ctx: Context map containing ::top-level-def-type
   
   Returns:
   - Updated context with error information if form type is not supported"
  [ctx]
  (let [form-type (::top-level-def-type ctx)]
    (if (= form-type "comment")
      {::error true
       ::message (str "Form type 'comment' is not supported for definition editing. "
                      "Please use 'form_edit' for editing comment blocks.")}
      ctx)))

(defn format-similar-matches
  "Formats a list of similar matches into suggestion strings.
   Each match should contain :tag, :form-name, and :qualified-name."
  [similar-matches]
  (when (seq similar-matches)
    (->> similar-matches
         (map (fn [{:keys [tag qualified-name]}]
                (str "- (" tag " " qualified-name " ...")))
         (str/join "\n")
         (str "\nDid you mean one of these?\n"))))

(defn find-form
  "Finds a top-level form in the source code.
   Requires ::zloc, ::top-level-def-type, and ::top-level-def-name in the context.
   Updates ::zloc to point to the found form or returns an error with suggestions
   if namesapced versions of the requested form are found."
  [ctx]
  (let [result (core/find-top-level-form
                (::zloc ctx)
                (::top-level-def-type ctx)
                (::top-level-def-name ctx)
                ;; provide a depth to catch forms inside reader conditionals
                3)
        form-zloc (:zloc result)
        similar-matches (:similar-matches result)]
    (if form-zloc
      (assoc ctx ::zloc form-zloc)
      (let [error-msg (str "Could not find form '" (::top-level-def-name ctx)
                           "' of type '" (::top-level-def-type ctx)
                           "' in file " (::file-path ctx))
            suggestion-msg (format-similar-matches similar-matches)]
        {::error true
         ::message (if suggestion-msg
                     (str error-msg suggestion-msg)
                     error-msg)}))))

(defn capture-form-position
  "Captures the column position of the found form for partial formatting.
   Must be called after find-form, before edit-form.
   Saves ::form-col (1-based column) to the context when position is available.
   Leaves ::form-col absent when z/position returns nil so downstream code
   can fall back to full-file formatting rather than mis-indenting.

   Requires ::zloc in the context (pointing to the found form)."
  [ctx]
  (let [zloc (::zloc ctx)]
    (if-let [pos (z/position zloc)]
      (assoc ctx ::form-col (second pos)) ;; [row col] -> col
      ctx))) ;; leave ::form-col absent — downstream falls back to full-file formatting

(defn format-new-source-partial
  "Formats the new source code in isolation when cljfmt is set to :partial
   and position tracking succeeded (::form-col is present in context).
   Formats ::new-source-code using cljfmt, then re-indents to match the
   target column position. This is done BEFORE insertion so the formatted
   content replaces the old form without touching the rest of the file.

   When ::form-col is absent (position tracking unavailable), passes through
   unchanged so format-source falls back to full-file formatting.

   Requires ::new-source-code, ::form-col, and ::nrepl-client-atom in context."
  [ctx]
  (let [nrepl-client-map (some-> ctx ::nrepl-client-atom deref)
        cljfmt-setting (config/get-cljfmt nrepl-client-map)]
    (if (and (= :partial cljfmt-setting) (::form-col ctx))
      (let [new-source (::new-source-code ctx)
            target-col (::form-col ctx)
            formatting-options (core/project-formatting-options nrepl-client-map)
            formatted (core/format-form-in-isolation new-source target-col formatting-options)]
        (assoc ctx ::new-source-code formatted))
      ;; Not :partial mode, or position unavailable — pass through
      ctx)))

(defn edit-form
  "Edits the form according to the specified edit type.
   Requires ::zloc, ::top-level-def-type, ::top-level-def-name, 
   ::new-source-code, and ::edit-type in the context.
   Updates ::zloc with the edited zipper."
  [ctx]
  (let [result (core/edit-top-level-form
                (::zloc ctx)
                (::top-level-def-type ctx)
                (::top-level-def-name ctx)
                (::new-source-code ctx)
                (::edit-type ctx))
        updated-zloc (:zloc result)
        similar-matches (:similar-matches result)]
    (if updated-zloc
      (assoc ctx ::zloc updated-zloc)
      (let [error-msg (str "Failed to " (name (::edit-type ctx)) " form.")
            suggestion-msg (format-similar-matches similar-matches)]
        {::error true
         ::message (if suggestion-msg
                     (str error-msg suggestion-msg)
                     error-msg)}))))

(defn zloc->output-source
  "Converts a zipper to a string output source.
   Requires ::zloc in the context.
   Adds ::output-source to the context with the string representation of the zipper."
  [ctx]
  (try
    (let [zloc (::zloc ctx)
          root-str (z/root-string zloc)]
      (assoc ctx ::output-source root-str))
    (catch Exception e
      {::error true
       ::message (str "Failed to convert zipper to string: " (.getMessage e))})))

(defn format-source
  "Formats the source code using the formatter.
   If formatting fails but the source is syntactically valid,
   returns the original source unchanged.

   When cljfmt is :partial AND the form was already formatted in isolation
   (indicated by ::form-col in context), skips whole-file formatting.
   Otherwise falls back to full-file formatting (e.g., for sexp-edit-pipeline
   which doesn't do pre-insertion partial formatting).

   Requires ::output-source in the context.
   Updates ::output-source with the formatted code (or unchanged if formatting fails)."
  [ctx]
  (let [nrepl-client-map (some-> ctx ::nrepl-client-atom deref)
        cljfmt-setting (config/get-cljfmt nrepl-client-map)]
    (cond
      ;; :partial mode with pre-formatted form - skip whole-file formatting
      ;; ::form-col presence means format-new-source-partial already ran
      (and (= :partial cljfmt-setting) (::form-col ctx))
      ctx

      ;; true (or :partial without pre-formatting) - full-file formatting
      cljfmt-setting
      (try
        (let [source (::output-source ctx)
              formatting-options (core/project-formatting-options nrepl-client-map)
              formatted (core/format-source-string source formatting-options)]
          (assoc ctx ::output-source formatted))
        (catch Exception e
          ;; Instead of failing, use the original source if available
          (if (::output-source ctx)
            ;; Return original source when formatting fails
            ctx
            ;; Only propagate error if we don't have valid source
            {::error true
             ::message (str "Failed to format source: " (.getMessage e))})))

      ;; false - formatting disabled, return ctx unchanged
      :else
      ctx)))

(defn determine-file-type
  "Determine if the file operation is a create or update.
   
   Arguments:
   - ctx: Context map containing ::file-path
   
   Returns:
   - Updated context with ::type added"
  [ctx]
  (let [file-exists? (get ctx ::file-exists? (.exists (java.io.File. (::file-path ctx))))]
    (assoc ctx ::type (if file-exists? "update" "create"))))

(defn generate-diff
  "Generate diff between old and new content as a pipeline function.
   Uses the shell-based diff generator from utils.clj.
   
   Arguments:
   - ctx: Context map containing ::old-content and ::output-source
   
   Returns:
   - Updated context with ::diff added"
  [{:keys [::old-content ::output-source] :as ctx}]
  (let [old-content (or old-content "")
        new-content (or output-source "")
        diff (if (= old-content new-content)
               "" ;; No diff if content is identical
               (try
                 ;; Use 3 lines of context
                 (diff-utils/generate-unified-diff old-content new-content)
                 (catch Exception e
                   ;; If shell diff fails, return a fallback message
                   (str "Changes made, but diff generation failed: " (.getMessage e)))))]
    (assoc ctx ::diff diff)))

(defn save-file
  "Saves the updated source to the file, creating parent directories if needed.
   Requires ::output-source and ::file-path in the context.
   Does not modify the context."
  [ctx]
  (try
    (let [file-path (::file-path ctx)
          output-source (::output-source ctx)
          file (io/file file-path)
          parent (.getParentFile file)]
      ;; Create parent directories if they don't exist
      (when (and parent (not (.exists parent)))
        (.mkdirs parent))
      ;; Save the file
      (let [save-result (core/save-file-content file-path output-source)]
        (if (:success save-result)
          ctx
          {::error true
           ::message (:message save-result)})))
    (catch Exception e
      {::error true
       ::message (str "Failed to save file: " (.getMessage e))})))

(defn update-file-timestamp
  "Updates the file timestamp after a successful edit.
   Uses the actual file modification time from the filesystem.
   
   Requires ::file-path and ::nrepl-client-atom in context."
  [ctx]
  (let [file-path (::file-path ctx)
        nrepl-client-atom (::nrepl-client-atom ctx)]
    (when (and nrepl-client-atom (not (::error ctx)))
      (file-timestamps/update-file-timestamp-to-current-mtime! nrepl-client-atom file-path))
    ctx))

;; Format result for tool consumption
(defn format-result
  "Format the result of the pipeline for tool consumption.
   
   Arguments:
   - ctx: The final context map from the pipeline
   
   Returns:
   - A map with :error, :message, and possibly :diff or :new-source, and :offsets keys"
  [ctx]
  (if (::error ctx)
    {:error true
     :message (::message ctx)}
    (let [dry_run (::dry-run ctx)
          result-map {:error false}]
      (cond-> result-map
        (::offsets ctx) (assoc :offsets (::offsets ctx))

        ;; Return new-source if dry-run is "new-source"
        (= dry_run "new-source")
        (assoc :new-source (::output-source ctx))

        ;; Otherwise return diff (default behavior and for "diff" dry-run)
        (and (::diff ctx) (not= dry_run "new-source"))
        (assoc :diff (::diff ctx))

        ;; Legacy: include :result if output-source exists
        (and (::output-source ctx) (not dry_run))
        (assoc :result [(::output-source ctx)])))))

;; Pipeline function definitions

(defn edit-form-pipeline
  "Pipeline for handling Clojure form editing operations.
   
   Arguments:
   - file-path: Path to the file to edit
   - form-name: Name of the form to edit (e.g., function name)
   - form-type: Type of form (e.g., 'defn', 'defmethod')
   - content-str: New content to insert
   - edit-type: Type of edit (:replace, :before, :after)
   - dry-run: Optional string, either \"diff\" or \"new-source\" to skip actual file write
   - config: Optional tool configuration map with :nrepl-client-atom
   
   Returns a context map with the result of the operation"
  [file-path form-name form-type content-str edit-type dry_run {:keys [nrepl-client-atom] :as config}]
  (let [ctx {::file-path file-path
             ::top-level-def-name form-name
             ::top-level-def-type form-type
             ::new-source-code content-str
             ::edit-type edit-type
             ::dry-run dry_run
             ::nrepl-client-atom nrepl-client-atom
             ::config config}]
    (thread-ctx
     ctx
     lint-repair-code
     validate-form-type
     load-source
     file-changes/capture-original-file-content
     check-file-modified
     enhance-defmethod-name
     parse-source
     find-form
     capture-form-position
     format-new-source-partial
     edit-form
     zloc->output-source
     format-source
     determine-file-type
     generate-diff
     (fn [ctx]
       (if (::dry-run ctx)
         ctx
         (-> ctx
             save-file
             update-file-timestamp))))))

(defn edit-sexp
  [{:keys [::zloc ::match-form ::new-form ::operation ::replace-all ::_whitespace-sensitive] :as ctx}]
  (try
    (if-let [result (core/find-and-edit-multi-sexp
                     zloc
                     match-form
                     new-form
                     (cond-> {:operation operation}
                       replace-all (assoc :all? true)))]
      (-> ctx
          (assoc ::zloc (:zloc result)))
      {::error true
       ::message (str "Could not find form: " match-form)})
    (catch Exception e
      {::error true
       ::message (str "Error editing form: " (.getMessage e))})))

(defn sexp-edit-pipeline
  "Pipeline for editing s-expressions in a file with support for replace, insert-before, and insert-after operations.
   
   Arguments:
   - file-path: Path to the file containing the forms
   - match-form: S-expression to find
   - new-form: S-expression to use for the operation
   - operation: The operation to perform (:replace, :insert-before, :insert-after)
   - replace-all: Whether to apply the operation to all occurrences
   - whitespace-sensitive: Whether to match forms exactly as written
   - dry_run: Optional string, either \"diff\" or \"new-source\" to skip actual file write
   - config: Optional tool configuration map with nrepl-client-atom
   
   Returns:
   - A context map with the result of the operation"
  [file-path match-form new-form operation replace-all whitespace-sensitive dry_run {:keys [nrepl-client-atom] :as config}]
  (let [ctx {::file-path file-path
             ::match-form match-form
             ::new-form new-form
             ::operation operation
             ::replace-all replace-all
             ::whitespace-sensitive whitespace-sensitive
             ::dry-run dry_run
             ::nrepl-client-atom nrepl-client-atom
             ::config config}]
    (thread-ctx
     ctx
     #(lint-repair-code % ::match-form)
     #(lint-repair-code % ::new-form)
     load-source
     file-changes/capture-original-file-content
     check-file-modified
     parse-source
     edit-sexp
     zloc->output-source
     format-source
     determine-file-type
     generate-diff
     (fn [ctx]
       (if (::dry-run ctx)
         ctx
         (-> ctx
             save-file
             update-file-timestamp))))))
