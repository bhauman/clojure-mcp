(ns clojure-mcp.tools.file-edit.pipeline
  "Pipeline architecture for file editing operations.
   Provides a thread-first pattern with error short-circuiting and
   standardized context maps for file editing."
  (:require
   [clojure-mcp.tools.file-edit.core :as core]
   [clojure-mcp.tools.form-edit.pipeline :as form-pipeline]
   [clojure-mcp.tools.form-edit.core :as form-edit-core]
   [clojure-mcp.tools.file-write.core :as file-write-core]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure-mcp.utils.emacs-integration :as emacs]
   [clojure-mcp.config :as config]
   [clojure-mcp.linting :as linting]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]))

;; We'll reuse many definitions from form-edit pipeline and add our own specific ones

;; Additional context map specs
(s/def ::old-string string?)
(s/def ::new-string string?)
(s/def ::nrepl-client-atom (s/nilable #(instance? clojure.lang.Atom %)))
;; Pipeline specific steps

;; Using check-file-modified from form-edit/pipeline instead

(defn validate-edit
  "Validates the file edit operation.
   Requires ::file-path, ::old-string, and ::new-string in the context."
  [ctx]
  (let [file-path (::form-pipeline/file-path ctx)
        old-string (::old-string ctx)
        new-string (::new-string ctx)
        source (::form-pipeline/source ctx) ;; This will be nil for non-existent files
        validation-result (core/validate-file-edit file-path old-string new-string source)]
    (if (:valid validation-result)
      ctx
      {::form-pipeline/error true
       ::form-pipeline/message (:message validation-result)})))

(defn perform-edit
  "Performs the actual edit on the file content.
   Requires ::file-path, ::old-string, ::new-string, and ::form-pipeline/source in the context.
   Adds ::form-pipeline/output-source to the context."
  [ctx]
  (let [file-path (::form-pipeline/file-path ctx)
        old-string (::old-string ctx)
        new-string (::new-string ctx)
        source (::form-pipeline/source ctx)
        ;; Get new content by performing the edit
        edited-content (core/perform-file-edit file-path old-string new-string source)]
    (assoc ctx ::form-pipeline/output-source edited-content)))

(defn format-clojure-content
  "Formats the content if it's a Clojure file.
   
   Arguments:
   - ctx: Context map containing ::form-pipeline/file-path and ::form-pipeline/output-source
   
   Returns:
   - Updated context with formatted content for Clojure files, or unchanged for other file types"
  [ctx]
  (let [file-path (::form-pipeline/file-path ctx)
        output-source (::form-pipeline/output-source ctx)
        nrepl-client-map (some-> ctx ::form-pipeline/nrepl-client-atom deref)
        cljfmt-enabled (config/get-cljfmt nrepl-client-map)
        formatting-options (form-edit-core/project-formatting-options nrepl-client-map)]
    (if (and (file-write-core/is-clojure-file? file-path)
             output-source
             cljfmt-enabled)
      (try
        (let [formatted-source (form-edit-core/format-source-string
                                output-source
                                formatting-options)]
          (assoc ctx ::form-pipeline/output-source formatted-source))
        (catch Exception e
          ctx))
      ctx)))

(defn capture-file-edit-offsets
  "Captures the position offsets of the edited region in a file.
   This function calculates character offsets for the edited region for highlighting.
   
   Requires ::form-pipeline/source and ::old-string in the context.
   Adds ::form-pipeline/offsets to the context when successful."
  [ctx]
  (try
    (let [source (::form-pipeline/source ctx)
          old-string (::old-string ctx)]
      (if (and source old-string (not-empty old-string))
        (let [start-offset (when-let [so (str/index-of source old-string)]
                             (inc so))
              end-offset (when start-offset (+ start-offset (count old-string)))]
          (if (and start-offset end-offset)
            (assoc ctx ::form-pipeline/offsets [start-offset end-offset])
            ctx))
        ctx))
    (catch Exception e
      ;; Don't fail the pipeline if offsets can't be captured, just log it
      ;; This allows non-Emacs workflows to continue
      (log/error e (str "Warning: Failed to capture edit offsets -" (.getMessage e)))
      ctx)))

;; This function is no longer needed - we'll use form-pipeline/highlight-form instead

;; Using update-file-timestamp from form-edit/pipeline instead

;; Define our file edit pipeline function that composes steps from form-edit pipeline and our own

(defn file-edit-pipeline
  "Pipeline for editing a file by replacing a string.
   
   Arguments:
   - file-path: Path to the file to edit
   - old-string: String to replace
   - new-string: New string to insert
   - nrepl-client-atom: Atom containing the nREPL client (optional)
   - config: Optional tool configuration map
   
   Returns:
   - A context map with the result of the operation"
  [file-path old-string new-string {:keys [nrepl-client-atom] :as config}]
  (let [initial-ctx {::form-pipeline/file-path file-path
                     ::old-string old-string
                     ::new-string new-string
                     ::form-pipeline/nrepl-client-atom nrepl-client-atom
                     ::form-pipeline/config config}]
    ;; Pipeline for existing file edit
    (form-pipeline/thread-ctx
     initial-ctx
     form-pipeline/emacs-buffer-modified-check
     form-pipeline/load-source ;; Load existing file
     form-pipeline/check-file-modified ;; Check if file modified since last read
     validate-edit ;; Validate the edit (uniqueness, etc.)
     capture-file-edit-offsets ;; Capture offsets for highlight
     perform-edit ;; Perform the actual edit
     ;; Only lint/repair Clojure files
     (fn [ctx]
       (let [file-path (::form-pipeline/file-path ctx)]
         (if (file-write-core/is-clojure-file? file-path)
           (form-pipeline/lint-repair-code ctx ::form-pipeline/output-source)
           ctx)))
     format-clojure-content ;; Format Clojure files automatically
     form-pipeline/determine-file-type ;; This will mark as "update"
     form-pipeline/generate-diff ;; Generate diff between old and new
     form-pipeline/save-file ;; Save the file
     form-pipeline/update-file-timestamp ;; Update the timestamp after save
     form-pipeline/highlight-form))) ;; Update the timestamp after save

;; Format result for tool consumption
(defn format-result
  "Format the result of the pipeline for tool consumption.
   
   Arguments:
   - ctx: The final context map from the pipeline
   
   Returns:
   - A map with :error, :message, and :diff keys, and potentially :repaired"
  [ctx]
  (if (::form-pipeline/error ctx)
    {:error true
     :message (::form-pipeline/message ctx)}
    (cond-> {:error false
             :diff (::form-pipeline/diff ctx)
             :type (::form-pipeline/type ctx)}
      ;; Include repaired flag if present
      (::form-pipeline/repaired ctx)
      (assoc :repaired true))))

(comment
  ;; === Examples of using the file-edit pipeline directly ===

  ;; Test file paths
  (def temp-dir (System/getProperty "java.io.tmpdir"))
  (def test-file (str temp-dir "/file-edit-test.txt"))

  ;; Create a test file
  (spit test-file "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n")

  ;; Test the pipeline with simple edit and Emacs highlighting enabled
  (def config {:enable-emacs-notifications true})
  (def result (file-edit-pipeline test-file "Line 3" "Line 3 - EDITED" config))
  (format-result result)

  ;; Test the pipeline with error (non-unique match)
  (def error-result (file-edit-pipeline test-file "Line" "EDITED Line" {}))
  (format-result error-result)

  ;; Test the pipeline with error (empty old_string)
  (def empty-string-result (file-edit-pipeline test-file "" "New content" {}))
  (format-result empty-string-result)

  ;; Clean up
  (.delete (io/file test-file)))


