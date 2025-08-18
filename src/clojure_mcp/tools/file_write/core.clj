(ns clojure-mcp.tools.file-write.core
  "Core implementation for the file-write tool.
   This namespace contains the pure functionality without any MCP-specific code."
  (:require
    [clojure.java.io :as io]
    [clojure-mcp.tools.form-edit.pipeline :as pipeline]
    [clojure-mcp.utils.diff :as diff-utils]
    [clojure-mcp.linting :as linting]
    [clojure-mcp.utils.valid-paths :as valid-paths]
    [rewrite-clj.zip :as z]))

  (defn is-clojure-file?
    "Check if a file is a Clojure-related file based on its extension or Babashka shebang.

     Parameters:
     - file-path: Path to the file to check

     Returns true for Clojure extensions (.clj, .cljs, .cljc, .edn, .bb) or files with a `bb` shebang."
    [file-path]
    (boolean (valid-paths/clojure-file? file-path)))

(defn write-clojure-file
  "Write content to a Clojure file, with linting, formatting, and diffing.
   
   Parameters:
   - file-path: Validated path to the file to write
   - content: Content to write to the file
   
   Returns:
   - A map with :error, :type, :file-path, and :diff keys"
  [nrepl-client-atom file-path content]
  (let [file (io/file file-path)
        file-exists? (.exists file)
        old-content (if file-exists? (slurp file) "")

        ;; Create a context map for the pipeline
        initial-ctx {::pipeline/nrepl-client-atom nrepl-client-atom
                     ::pipeline/file-path file-path
                     ::pipeline/source old-content
                     ::pipeline/new-source-code content
                     ::pipeline/old-content old-content
                     ::pipeline/file-exists? file-exists?}

        ;; Use thread-ctx to run the pipeline
        result (pipeline/thread-ctx
                initial-ctx
                pipeline/lint-repair-code
                (fn [ctx]
                  (assoc ctx ::pipeline/output-source (::pipeline/new-source-code ctx)))
                pipeline/format-source ;; Format the content
                pipeline/generate-diff ;; Generate diff between old and new content
                pipeline/determine-file-type ;; Determine if creating or updating
                pipeline/save-file)] ;; Save the file and get offsets

    ;; Format the result for tool consumption
    (if (::pipeline/error result)
      {:error true
       :message (::pipeline/message result)}
      {:error false
       :type (::pipeline/type result)
       :file-path (::pipeline/file-path result)
       :diff (::pipeline/diff result)})))

(defn write-text-file
  "Write content to a non-Clojure text file, with diffing but no linting or formatting.
   
   Parameters:
   - file-path: Validated path to the file to write
   - content: Content to write to the file
   
   Returns:
   - A map with :error, :type, :file-path, and :diff keys"
  [file-path content]
  (try
    (let [file (io/file file-path)
          file-exists? (.exists file)
          old-content (if file-exists? (slurp file) "")
          ;; Only generate diff if the file already exists
          diff (if file-exists?
                 (if (= old-content content)
                   ""
                   (diff-utils/generate-unified-diff old-content content))
                 "")]

      ;; Write the content directly
      (spit file content)

      {:error false
       :type (if file-exists? "update" "create")
       :file-path file-path
       :diff diff})
    (catch Exception e
      {:error true
       :message (str "Error writing file: " (.getMessage e))})))

(defn write-file
  "Write content to a file, detecting the file type and using appropriate processing.
   For Clojure files (.clj, .cljs, .cljc, .edn), applies linting and formatting.
   For other file types, writes directly with no processing.
   
   Parameters:
   - file-path: Validated path to the file to write
   - content: Content to write to the file
   
   Returns:
   - A map with :error, :type, :file-path, and :diff keys"
  [nrepl-client-atom file-path content]
  (if (is-clojure-file? file-path)
    (write-clojure-file nrepl-client-atom file-path content)
    (write-text-file file-path content)))
