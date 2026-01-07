(ns clojure-mcp.tools.paren-repair.core
  "Core logic for parenthesis/delimiter repair using parinfer."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure-mcp.config :as config]
   [clojure-mcp.delimiter :as delimiter]
   [clojure-mcp.sexp.paren-utils :as paren-utils]
   [clojure-mcp.tools.form-edit.core :as form-edit-core]
   [clojure-mcp.utils.diff :as diff]
   [clojure-mcp.utils.file :as file-utils]))

(def clojure-extensions
  "Set of file extensions considered Clojure files."
  #{".clj" ".cljs" ".cljc" ".edn"})

(defn clojure-file?
  "Returns true if the file path has a Clojure file extension."
  [file-path]
  (let [path-str (str file-path)]
    (some #(string/ends-with? path-str %) clojure-extensions)))

(defn repair-file!
  "Repairs delimiter errors in a Clojure file and optionally formats it.

   Arguments:
   - nrepl-client-map: The nREPL client map containing config
   - file-path: Absolute path to the file to repair

   Returns a map with:
   - :success - boolean indicating overall success
   - :file-path - the processed file path
   - :message - human-readable status message
   - :delimiter-fixed - boolean (was delimiter error fixed?)
   - :formatted - boolean (was cljfmt applied?)
   - :diff - unified diff of changes (if any)"
  [nrepl-client-map file-path]
  (let [file (io/file file-path)]
    (cond
      ;; File doesn't exist
      (not (.exists file))
      {:success false
       :file-path file-path
       :message "File does not exist"
       :delimiter-fixed false
       :formatted false
       :diff nil}

      ;; Not a Clojure file
      (not (clojure-file? file-path))
      {:success false
       :file-path file-path
       :message "Not a Clojure file (skipping)"
       :delimiter-fixed false
       :formatted false
       :diff nil}

      :else
      (try
        (let [original-content (file-utils/slurp-utf8 file-path)
              has-delimiter-error? (delimiter/delimiter-error? original-content)

              ;; Step 1: Try to repair delimiter errors if present
              repaired-content (if has-delimiter-error?
                                 (paren-utils/parinfer-repair original-content)
                                 original-content)

              ;; If repair failed, we can't proceed
              _ (when (and has-delimiter-error? (nil? repaired-content))
                  (throw (ex-info "Could not repair delimiter errors"
                                  {:file-path file-path})))

              delimiter-fixed? (and has-delimiter-error?
                                    (some? repaired-content)
                                    (not= original-content repaired-content))

              ;; Step 2: Format with cljfmt if enabled
              cljfmt-enabled? (config/get-cljfmt nrepl-client-map)
              formatting-options (when cljfmt-enabled?
                                   (form-edit-core/project-formatting-options nrepl-client-map))

              final-content (if cljfmt-enabled?
                              (try
                                (form-edit-core/format-source-string
                                 (or repaired-content original-content)
                                 formatting-options)
                                (catch Exception _
                                  ;; If formatting fails, use the repaired content
                                  (or repaired-content original-content)))
                              (or repaired-content original-content))

              formatted? (and cljfmt-enabled?
                              (not= (or repaired-content original-content) final-content))

              changed? (not= original-content final-content)]

          (if changed?
            (do
              ;; Write the file
              (file-utils/spit-utf8 file-path final-content)

              ;; Generate diff
              (let [diff-str (diff/generate-unified-diff original-content final-content)
                    status-parts (cond-> []
                                   delimiter-fixed? (conj "delimiter-fixed")
                                   formatted? (conj "formatted"))]
                {:success true
                 :file-path file-path
                 :message (str "Fixed [" (string/join ", " status-parts) "]")
                 :delimiter-fixed delimiter-fixed?
                 :formatted formatted?
                 :diff diff-str}))

            ;; No changes needed
            {:success true
             :file-path file-path
             :message "No changes needed (no delimiter errors)"
             :delimiter-fixed false
             :formatted false
             :diff nil}))

        (catch clojure.lang.ExceptionInfo e
          {:success false
           :file-path file-path
           :message (ex-message e)
           :delimiter-fixed false
           :formatted false
           :diff nil})

        (catch Exception e
          {:success false
           :file-path file-path
           :message (str "Error: " (.getMessage e))
           :delimiter-fixed false
           :formatted false
           :diff nil})))))
