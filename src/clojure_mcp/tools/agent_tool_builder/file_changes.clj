(ns clojure-mcp.tools.agent-tool-builder.file-changes
  "Tracks file changes during agent execution"
  (:require
   [clojure.java.io :as io]
   [clojure-mcp.utils.diff :as diff-utils]
   [clojure-mcp.utils.file :as file-utils]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defn reset-changed-files!
  "Reset the changed-files map to empty.
   
   Args:
   - nrepl-client-atom: The nREPL client atom"
  [nrepl-client-atom]
  (swap! nrepl-client-atom assoc :clojure-mcp.agent-tool-builder/changed-files {}))

(defn capture-original-content!
  "Captures the original content of a file if not already captured.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   - file-path: Path to the file
   - content: Current content of the file
   
   Returns: The content (unchanged)"
  [nrepl-client-atom file-path content]
  (when nrepl-client-atom
    (try
      (let [canonical-path (.getCanonicalPath (io/file file-path))
            changed-files-key :clojure-mcp.agent-tool-builder/changed-files
            changed-files (get @nrepl-client-atom changed-files-key {})]
        (when-not (contains? changed-files canonical-path)
          (swap! nrepl-client-atom assoc-in [changed-files-key canonical-path] content)))
      (catch Exception e
        (log/warn "Failed to capture original content for" file-path "-" (.getMessage e)))))
  content)

(defn capture-original-file-content
  "Pipeline step that captures the original file content before any edits.
   Only captures if the file hasn't been seen before in this agent session.
   
   Expects :clojure-mcp.tools.form-edit.pipeline/file-path and 
   :clojure-mcp.tools.form-edit.pipeline/source (or old-content) in the context.
   Returns context unchanged."
  [ctx]
  (let [nrepl-client-atom (:clojure-mcp.tools.form-edit.pipeline/nrepl-client-atom ctx)
        file-path (:clojure-mcp.tools.form-edit.pipeline/file-path ctx)
        content (or (:clojure-mcp.tools.form-edit.pipeline/source ctx)
                    (:clojure-mcp.tools.form-edit.pipeline/old-content ctx))]
    (when (and nrepl-client-atom file-path content)
      (capture-original-content! nrepl-client-atom file-path content))
    ctx))

(defn format-file-diff
  "Formats a diff for a single file.
   
   Args:
   - file-path: The canonical path to the file
   - original-content: Original content (may be empty string for new files)
   - current-content: Current content of the file
   
   Returns: Formatted diff string or error message"
  [file-path original-content current-content]
  (try
    (let [diff (if (= original-content current-content)
                 "No changes"
                 (diff-utils/generate-unified-diff
                  (or original-content "")
                  (or current-content "")))]
      (str "#### " file-path "\n```diff\n" diff "\n```\n"))
    (catch Exception e
      (str "#### " file-path "\n```\nError generating diff: " (.getMessage e) "\n```\n"))))

(defn generate-all-diffs
  "Generates diffs for all changed files.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   
   Returns: String with all formatted diffs"
  [nrepl-client-atom]
  (try
    (let [changed-files (get @nrepl-client-atom :clojure-mcp.agent-tool-builder/changed-files {})]
      (if (empty? changed-files)
        ""
        (let [diffs (for [[canonical-path original-content] changed-files]
                      (let [current-file (io/file canonical-path)]
                        (if (.exists current-file)
                          (let [current-content (file-utils/slurp-utf8 current-file)]
                            (format-file-diff canonical-path original-content current-content))
                          ;; File was deleted
                          (format-file-diff canonical-path original-content ""))))]
          (str "## File Changes\n\n" (str/join "\n" diffs) "\n"))))
    (catch Exception e
      (str "## File Changes\n\nError collecting file changes: " (.getMessage e) "\n\n"))))

(defn should-track-changes?
  "Checks if file change tracking is enabled for agents.
   
   Args:
   - agent-config: The agent configuration map
   
   Returns: Boolean indicating if tracking is enabled"
  [agent-config]
  ;; Default to true, but allow disabling via :track-file-changes false
  (get agent-config :track-file-changes true))
