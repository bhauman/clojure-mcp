(ns clojure-mcp.tools.form-edit.pipeline
  "Pipeline architecture for form editing operations.
   Provides a thread-first pattern with error short-circuiting and
   standardized context maps."
  (:require
   [clojure-mcp.tools.form-edit.core :as core]
   [clojure-mcp.tools.agent-tool-builder.file-changes :as file-changes]
   [clojure-mcp.utils.emacs-integration :as emacs]
   [clojure-mcp.utils.diff :as diff-utils]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure-mcp.config :as config]
   [rewrite-clj.zip :as z]
   [rewrite-clj.parser :as p]
   [clojure-mcp.linting :as linting]
   [clojure-mcp.sexp.paren-utils :as paren-utils]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]))

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

(s/def ::context
  (s/keys :req [::file-path]
          :opt [::source ::old-content ::new-source-code ::top-level-def-name
                ::top-level-def-type ::edit-type ::error ::message
                ::zloc ::offsets ::lint-result ::docstring
                ::comment-substring ::new-content ::expand-symbols
                ::diff ::type ::output-source ::nrepl-client-atom ::dry-run]))

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

(defn log-pipeline-ctx
  ([message ctx]
   (log-pipeline-ctx message ctx :debug))
  ([message ctx level]
   (case level
     :info (log/info (str "PIPELINE " message ": ") (pr-str ctx))
     :warn (log/warn (str "PIPELINE " message ": ") (pr-str ctx))
     :error (log/error (str "PIPELINE " message ": ") (pr-str ctx))
     (log/debug (str "PIPELINE " message ": ") (pr-str ctx)))
   ctx))

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

(defn emacs-buffer-modified-check
  "Check if the emacs buffer is modified and saves it. When used before
  check-file-modifed, it will trigger the modification error.
  
  A modified buffer is another level to check for file modifications.  

  Marking it as modified. This in turn will trigger the check last modified 
  to error out requiring the LLM to read the file before editing."
  [ctx]
  (let [file-path (::file-path ctx)
        config (::config ctx)]
    (if (and (emacs/config-enables-emacs-notifications? config)
             (emacs/save-emacs-buffer-if-modified file-path))
      ;; prevent race condition of write and then relying on read
      (assoc ctx ::file-modifed true)
      ctx)))

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
                        "\nPlease read the WHOLE file again with `collapse: false` before editing.")}
        ctx))))

(defn lint-code
  "Lints the new source code to be inserted.
   Adds ::lint-result to the context."
  ([ctx]
   (lint-code ctx ::new-source-code))
  ([ctx ky]
   (let [lang (file-path->lang (::file-path ctx))
         lint-result (linting/lint (get ctx ky) {:lang lang})]
     (if (and lint-result (:error? lint-result))
       {::error :lint-failure
        ::lint-report (:report lint-result)
        ::message (str "Syntax errors detected in Clojure code:\n"
                       (:report lint-result)
                       "\nPlease fix the syntax errors before saving.")}
       (assoc ctx ::lint-result lint-result)))))

(defn lint-repair-code
  "Lints the new source code to be inserted, and attempts to fix delimiter errors.
   If repair is successful, updates the source code in the context.
   Adds ::lint-result and potentially ::repaired to the context."
  ([ctx]
   (lint-repair-code ctx ::new-source-code))
  ([ctx ky]
   (let [original-code (get ctx ky)
         lang (file-path->lang (::file-path ctx))
         lint-result (linting/lint original-code {:lang lang})]
     (if (and lint-result (:error? lint-result))
       (if (paren-utils/has-delimiter-errors? lint-result)
         (if-let [repaired-code (paren-utils/parinfer-repair original-code)]
           (-> ctx
               (assoc ky repaired-code)
               (assoc ::repaired true)
               (assoc ::original-code original-code)
               (assoc ::lint-result nil))
           {::error :lint-failure
            ::lint-report (:report lint-result)
            ::message (str "Delimiter errors detected in Clojure code and automatic repair failed:\n"
                           (:report lint-result)
                           "\nPlease fix the syntax errors before saving.")})
         {::error :lint-failure
          ::lint-report (:report lint-result)
          ::message (str "Syntax errors detected in Clojure code:\n"
                         (:report lint-result)
                         "\nPlease fix the syntax errors before saving.")})
       ;; No lint errors, just add the result to the context
       (assoc ctx ::lint-result lint-result)))))

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

(defn capture-edit-offsets
  "Captures the position offsets of the current zipper location.
   This should be called immediately after editing operations while position information is valid.
   
   Requires ::zloc in the context.
   Adds ::offsets to the context when successful."
  [ctx]
  (try
    (let [zloc (::zloc ctx)
          positions (z/position-span zloc)
          output-source (or (::output-source ctx) (z/root-string zloc))
          offsets (core/zloc-offsets output-source positions)]
      (assoc ctx ::offsets offsets))
    (catch Exception e
      ;; Don't fail the pipeline if offsets can't be captured, just log it
      ;; This allows non-Emacs workflows to continue
      (log/error e "Warning: Failed to capture edit offsets -" (.getMessage e))
      ctx)))

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

(defn edit-docstring
  "Edits the docstring of a form.
   Requires ::zloc, ::top-level-def-type, ::top-level-def-name, and ::docstring in the context.
   Updates ::zloc with the edited zipper."
  [ctx]
  (let [result (core/edit-docstring
                (::zloc ctx)
                (::top-level-def-type ctx)
                (::top-level-def-name ctx)
                (::docstring ctx))
        updated-zloc (:zloc result)
        similar-matches (:similar-matches result)]
    (if updated-zloc
      (assoc ctx ::zloc updated-zloc)
      (let [error-msg (str "Could not find or edit docstring for form '"
                           (::top-level-def-name ctx) "'.")
            suggestion-msg (format-similar-matches similar-matches)]
        {::error true
         ::message (if suggestion-msg
                     (str error-msg suggestion-msg)
                     error-msg)}))))

(defn find-and-edit-comment
  "Finds and edits a comment block.
   Requires ::source, ::comment-substring, and ::new-content in the context.
   Updates ::zloc with the edited zipper."
  [ctx]
  (let [source (::source ctx)
        comment-substring (::comment-substring ctx)
        new-content (::new-content ctx)
        block (core/find-comment-block source comment-substring)]
    (if (nil? block)
      {::error true
       ::message (str "Could not find comment containing: " comment-substring)}
      (case (:type block)
        ;; For comment forms, use zipper replacement
        :comment-form
        (let [zloc (:zloc block)
              updated-zloc (z/replace zloc (p/parse-string new-content))]
          (assoc ctx ::zloc updated-zloc))
        ;; For line comments, create a new zipper with the edited content
        :line-comments
        (let [lines (str/split-lines source)
              start (:start block)
              end (:end block)
              new-lines (str/split-lines new-content)
              replaced-lines (concat
                              (take start lines)
                              new-lines
                              (drop (inc end) lines))
              updated-source (str/join "\n" replaced-lines)
              ;; Parse with track-position? to ensure positions are tracked correctly
              updated-zloc (z/of-string updated-source {:track-position? true})]
          (assoc ctx ::zloc updated-zloc))))))

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
   
   Requires ::output-source in the context.
   Updates ::output-source with the formatted code (or unchanged if formatting fails)."
  [ctx]
  (let [nrepl-client-map (some-> ctx ::nrepl-client-atom deref)
        cljfmt-enabled (config/get-cljfmt nrepl-client-map)]
    (if cljfmt-enabled
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
      ;; If cljfmt is disabled, return ctx unchanged
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

#_(defn emacs-set-auto-revert
    "Ensures that the file is open in Emacs with auto-revert-mode enabled if notifications are enabled.
   Requires ::file-path and ::config in the context."
    [ctx]
    (try
      (let [file-path (::file-path ctx)
            config (::config ctx)]
      ;; Only notify if emacs notifications are enabled in config
        (if (emacs/config-enables-emacs-notifications? config)
          (do
            (emacs/ensure-auto-revert file-path) ;; Now ensure-auto-revert is always async
            ctx)
        ;; Otherwise return context unchanged
          ctx))
    ;; Fail silently if emacs isn't started
      (catch Exception _
      ;; Return context unchanged if Emacs integration fails
        ctx)))

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

(defn highlight-form
  "Highlights the edited form in Emacs if notifications are enabled.
   Requires ::file-path, ::offsets, and ::config in the context."
  [ctx]
  (try
    (let [[start end] (::offsets ctx)
          config (::config ctx)]
      ;; Only notify if emacs notifications are enabled in config
      (when (emacs/config-enables-emacs-notifications? config)
        (emacs/highlight-region (::file-path ctx) start end 2.0))
      ctx)
    ;; Fail silently to support non-emacs workflow
    (catch Exception _
      ;; Return context unchanged if highlighting fails
      ctx)))

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
     emacs-buffer-modified-check
     load-source
     file-changes/capture-original-file-content
     check-file-modified
     enhance-defmethod-name
     parse-source
     find-form
     edit-form
     capture-edit-offsets
     zloc->output-source
     format-source
     determine-file-type
     generate-diff
     (fn [ctx]
       (if (::dry-run ctx)
         ctx
         (-> ctx
             save-file
             update-file-timestamp
             highlight-form))))))

(defn docstring-edit-pipeline
  "Pipeline for editing a docstring in a file.
   
   Arguments:
   - file-path: Path to the file containing the form
   - form-name: Name of the form to edit
   - form-type: Type of the form (e.g., \"defn\", \"def\")
   - new-docstring: New docstring content
   - nrepl-client-atom: Atom containing the nREPL client (optional)
   - config: Optional tool configuration map
   
   Returns:
   - A context map with the result of the operation"
  [file-path form-name form-type new-docstring {:keys [nrepl-client-atom] :as config}]
  (let [ctx {::file-path file-path
             ::top-level-def-name form-name
             ::top-level-def-type form-type
             ::docstring new-docstring
             ::edit-type :docstring
             ::nrepl-client-atom nrepl-client-atom
             ::config config}]
    (thread-ctx
     ctx
     validate-form-type
     emacs-buffer-modified-check
     load-source
     file-changes/capture-original-file-content
     check-file-modified
     enhance-defmethod-name
     parse-source
     find-form
     edit-docstring
     capture-edit-offsets
     zloc->output-source
     format-source
     determine-file-type
     generate-diff
     save-file
     update-file-timestamp
     highlight-form)))

(defn comment-block-edit-pipeline
  "Pipeline for editing a comment block in a file.
   
   Arguments:
   - file-path: Path to the file containing the comment
   - comment-substring: Substring to identify the comment block
   - new-content: New content for the comment block
   - nrepl-client-atom: Atom containing the nREPL client (optional)
   - config: Optional tool configuration map
   
   Returns:
   - A context map with the result of the operation"
  [file-path comment-substring new-content {:keys [nrepl-client-atom] :as config}]
  (let [ctx {::file-path file-path
             ::comment-substring comment-substring
             ::new-content new-content
             ::nrepl-client-atom nrepl-client-atom
             ::config config}]
    (thread-ctx
     ctx
     emacs-buffer-modified-check
     load-source
     file-changes/capture-original-file-content
     check-file-modified
     find-and-edit-comment
     capture-edit-offsets
     zloc->output-source
     determine-file-type
     generate-diff
     ;; TODO this should probably be added
     ;; #(lint-code % ::output-source)

     save-file
     update-file-timestamp
     highlight-form)))

(defn edit-locations->offsets [ctx]
  (try
    (let [zloc (::zloc ctx)
          positions (last (::edit-locations ctx))
          output-source (or (::output-source ctx) (z/root-string zloc))
          offsets (core/zloc-offsets output-source positions)]
      (assoc ctx ::offsets offsets))
    (catch Exception e
      ;; Don't fail the pipeline if offsets can't be captured, just log it
      ;; This allows non-Emacs workflows to continue
      (log/error e (str "Warning: Failed to capture edit offsets -" (ex-message e)))
      ctx)))

(defn replace-sexp
  [{:keys [::zloc ::match-form ::new-form ::replace-all ::whitespace-sensitive] :as ctx}]
  (try
    (if-let [result (core/find-and-edit-multi-sexp
                     zloc
                     match-form
                     new-form
                     (cond-> {:operation :replace}
                       replace-all (assoc :all? true)))]
      (-> ctx
          (assoc ::zloc (:zloc result))
          ;; not used
          (assoc ::edit-locations (:locations result)))
      {::error true
       ::message (str "Could not find form: " match-form)})
    (catch Exception e
      {::error true
       ::message (str "Error replacing form: " (.getMessage e))})))

(defn edit-sexp
  [{:keys [::zloc ::match-form ::new-form ::operation ::replace-all ::whitespace-sensitive] :as ctx}]
  (try
    (if-let [result (core/find-and-edit-multi-sexp
                     zloc
                     match-form
                     new-form
                     (cond-> {:operation operation}
                       replace-all (assoc :all? true)))]
      (-> ctx
          (assoc ::zloc (:zloc result))
          (assoc ::edit-locations (:locations result)))
      {::error true
       ::message (str "Could not find form: " match-form)})
    (catch Exception e
      {::error true
       ::message (str "Error editing form: " (.getMessage e))})))

(defn sexp-replace-pipeline
  "Pipeline for replacing s-expressions in a file.
   
   Arguments:
   - file-path: Path to the file containing the forms
   - match-form: S-expression to find and replace
   - new-form: Replacement s-expression
   - replace-all: Whether to replace all occurrences
   - whitespace-sensitive: Whether to match forms exactly as written
   - nrepl-client-atom: Atom containing the nREPL client (optional)
   - config: Optional tool configuration map
   
   Returns:
   - A context map with the result of the operation"
  [file-path match-form new-form replace-all whitespace-sensitive {:keys [nrepl-client-atom] :as config}]
  (let [ctx {::file-path file-path
             ::match-form match-form
             ::new-form new-form
             ::replace-all replace-all
             ::whitespace-sensitive whitespace-sensitive
             ::nrepl-client-atom nrepl-client-atom
             ::config config}]
    (thread-ctx
     ctx
     #(lint-repair-code % ::match-form)
     #(lint-repair-code % ::new-form)
     emacs-buffer-modified-check
     load-source
     file-changes/capture-original-file-content
     check-file-modified
     parse-source
     replace-sexp
     zloc->output-source
     edit-locations->offsets
     format-source
     determine-file-type
     generate-diff
     save-file
     update-file-timestamp
     highlight-form)))

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
     emacs-buffer-modified-check
     load-source
     file-changes/capture-original-file-content
     check-file-modified
     parse-source
     edit-sexp
     zloc->output-source
     edit-locations->offsets
     format-source
     determine-file-type
     generate-diff
     (fn [ctx]
       (if (::dry-run ctx)
         ctx
         (-> ctx
             save-file
             update-file-timestamp
             highlight-form))))))

(comment
  ;; Example usage of the pipelines
  (sexp-replace-pipeline "test-sexp.clj"
                         "(* y y)"
                         "(+ x (* y y))"
                         false
                         false
                         {})

  (def replace-result
    (edit-form-pipeline "tmp/edit_file_created.clj"
                        "simple-fn"
                        "defn"
                        "(defn example-fn [x y]\n  (* x y))"
                        :after
                        {:enable-emacs-notifications true}))

  (def docstring-result
    (docstring-edit-pipeline "/path/to/file.clj"
                             "example-fn"
                             "defn"
                             "Updated docstring"
                             {}))

  (def comment-result
    (comment-block-edit-pipeline "/path/to/file.clj"
                                 "test comment"
                                 ";; Updated comment"
                                 {}))

  (def outline-result
    (file-outline-pipeline "/path/to/file.clj" [])))
