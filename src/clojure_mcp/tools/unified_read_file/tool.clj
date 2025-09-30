(ns clojure-mcp.tools.unified-read-file.tool
  "Implementation of the unified-read-file tool using the tool-system multimethod approach.
   This tool combines the functionality of fs_read_file and clojure_read_file into a single
   smart tool that automatically selects the appropriate mode based on file type."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure-mcp.utils.valid-paths :as valid-paths]
   [clojure-mcp.tools.unified-read-file.pattern-core :as pattern-core]
   [clojure-mcp.tools.unified-read-file.core :as core]
   [clojure-mcp.file-content :as file-content]
   [clojure-mcp.config :as config]
   [clojure.tools.logging :as log]
   [clojure.string :as str]))

;; Factory function to create the tool configuration
(defn create-unified-read-file-tool
  "Creates the unified-read-file tool configuration with optional parameters."
  ([nrepl-client-atom]
   (create-unified-read-file-tool nrepl-client-atom {}))
  ([nrepl-client-atom {:keys [max-lines max-line-length]
                       :or {max-lines 2000
                            max-line-length 1000}}]
   ;; TODO this is naive and is a problem for files that are just one line
   {:tool-type :unified-read-file
    :nrepl-client-atom nrepl-client-atom
    :max-lines max-lines
    :max-line-length max-line-length}))

 ;; Helper functions

(defn collapsible-clojure-file?
  "Determines if a file is a collapsible Clojure source file based on its extension."
  [file-path]
  (when file-path
    (let [extension (last (str/split file-path #"\."))]
      (contains? #{"clj" "cljc" "cljs" "bb" "lpy"} extension))))

;; Implement the required multimethods for the unified read file tool
(defmethod tool-system/tool-name :unified-read-file [_]
  "read_file")

(defmethod tool-system/tool-description :unified-read-file [{:keys [max-lines max-line-length]}]
  (str "Smart file reader with pattern-based exploration for Clojure files.
   
For Clojure files (.clj, .cljc, .cljs, .bb, .lpy):

This tool defaults to an expandable collapsed view to quickly grab the information you need from a Clojure file.
If called without `name_pattern` or `content_pattern` it will return the file content where 
you will see only function signatures. This gives you a quick overview of the file.

When you want to see more, this tool has a grep functionality where you
can give patterns to match the names for top level definitions
`name_pattern` or match the bodies of top level definitions `content_pattern`.

The functions that match these patterns will be the only functions expanded in collapsed view.

For defmethod forms:
- The name includes both the method name and the dispatch value (e.g., \"area :rectangle\")
- For vector dispatch values, use the exact pattern (e.g., \"dispatch-with-vector \\[:feet :inches\\]\")
- For qualified/namespaced multimethod names, include the namespace (e.g., \"tool-system/validate-inputs :clojure-eval\")

For text files (non-Clojure):
- When `collapsed: true` and a pattern is provided (`content_pattern` or `name_pattern`), shows only lines matching the pattern with 10 lines of context before and after

For all other file types:
- Collapsed view will not be applied and will return the raw contents of the file

Parameters:
- path: Path to the file (required)
- collapsed: Show collapsed view (default: true)

Collapsed View mode function expansion parameters:

- name_pattern: Regex to match function names (e.g., \"validate.*\")
- content_pattern: Regex to match function content (e.g., \"try|catch\")

Example usage for defmethod forms:
- Find all implementations of a multimethod: `name_pattern: \"area\"`
- Find specific dispatch values: `name_pattern: \"area :circle\"`
- Find vector dispatch values: `name_pattern: \"convert-length \\\\[:feet :inches\\\\]\"`
- Find namespace-qualified methods: `name_pattern: \"tool-system/validate-inputs\"`

Example usage for text files:
- Find error messages: `content_pattern: \"ERROR|WARN\"`
- Find specific log entries: `content_pattern: \"\\\\[2024-01-15.*ERROR\\\\]\"`

Non collapsed view mode respects these parameters:

- line_offset: Line to start reading from (non-collapsed mode only, default: 0)
- limit: Maximum lines to read (non-collapsed mode only, default: " max-lines ")

By default, reads up to " max-lines " lines, truncating lines longer than " max-line-length " characters."))

(defmethod tool-system/tool-schema :unified-read-file [_]
  {:type :object
   :properties {:path {:type :string
                       :description "Path to the file to read"}
                :collapsed {:type :boolean
                            :description "Whether to show collapsed view for Clojure files (default: true)"}
                :name_pattern {:type :string
                               :description "Pattern to match function names (e.g., \"validate.*\")"}
                :content_pattern {:type :string
                                  :description "Pattern to match function content (e.g., \"try|catch\")"}
                :line_offset {:type :integer
                              :description "Line to start reading from for raw mode (default: 0)"}
                :limit {:type :integer
                        :description "Maximum lines to read (default: 2000)"}}
   :required [:path]})

(defmethod tool-system/validate-inputs :unified-read-file [{:keys [nrepl-client-atom]} inputs]
  (let [{:keys [path collapsed name_pattern content_pattern line_offset limit]} inputs
        nrepl-client @nrepl-client-atom]
    (when-not path
      (throw (ex-info "Missing required parameter: path" {:inputs inputs})))

    (when (and name_pattern (not= name_pattern ""))
      (try (re-pattern name_pattern)
           (catch Exception e
             (throw (ex-info (str "Invalid name_pattern regex: " (.getMessage e))
                             {:pattern name_pattern})))))

    (when (and content_pattern (not= content_pattern ""))
      (try (re-pattern content_pattern)
           (catch Exception e
             (throw (ex-info (str "Invalid content_pattern regex: " (.getMessage e))
                             {:pattern content_pattern})))))

    (let [validated-path (valid-paths/validate-path-with-client path nrepl-client)]

      (when-not (valid-paths/path-exists? validated-path)
        (throw
         (ex-info (format "Invalid Path: file `%s` does not exist." path)
                  {:inputs inputs})))

      {:path validated-path
       :collapsed (if (nil? collapsed) true collapsed)
       :name_pattern name_pattern
       :content_pattern content_pattern
       :line_offset (or line_offset 0)
       :limit limit})))

(defmethod tool-system/execute-tool :unified-read-file [{:keys [max-lines max-line-length nrepl-client-atom]} inputs]
  (let [{:keys [path collapsed name_pattern content_pattern include_comments line_offset limit]} inputs
        limit-val (or limit max-lines)
        is-clojure-file (collapsible-clojure-file? path)
        ;; Get write-file-guard config if we have the atom
        write-file-guard (when nrepl-client-atom
                           (config/get-write-file-guard @nrepl-client-atom))
        ;; For text files, we'll use content_pattern or name_pattern for matching
        pattern-for-text (or content_pattern name_pattern)]

    (cond
      (and is-clojure-file collapsed)
      (try
        (let [result (pattern-core/generate-collapsed-view
                      path
                      name_pattern
                      content_pattern)]
          ;; Update timestamp for collapsed reads if write-file-guard is :partial-read
          (when (and nrepl-client-atom (= write-file-guard :partial-read))
            (file-timestamps/update-file-timestamp-to-current-mtime! nrepl-client-atom path))
          {:mode :clojure
           :content (:view result)
           :path path
           :pattern-info (:pattern-info result)
           :error false})
        (catch Exception e
          {:error true
           :message (.getMessage e)}))

      ;; New case: text file with collapsed view and pattern
      (and (file-content/text-file? path)
           (not is-clojure-file)
           collapsed
           pattern-for-text)
      (let [file-result (file-timestamps/read-file-with-timestamp
                         nrepl-client-atom path 0 limit-val :max-line-length max-line-length)]
        (if (:error file-result)
          {:error true
           :message (:error file-result)}
          (let [text-view-result (core/generate-text-collapsed-view
                                  (:content file-result)
                                  pattern-for-text
                                  10 ; context-before
                                  10)] ; context-after
            (if (:error text-view-result)
              text-view-result
              {:mode :text-collapsed
               :content (:view text-view-result)
               :path path
               :pattern-info (:pattern-info text-view-result)
               :error false}))))

      (or is-clojure-file (file-content/text-file? path))
      (let [result (file-timestamps/read-file-with-timestamp
                    nrepl-client-atom path line_offset limit-val :max-line-length max-line-length)]
        (if (:error result)
          {:error true
           :message (:error result)}
          (assoc result :mode :raw)))

      (file-content/image-file? path)
      (try
        (assoc (file-content/->file-response path)
               :mode :file-response)
        (catch Exception e
          (let [message (str "Error: creating file response for " path)]
            (log/error e message)
            {:error true
             :message message})))

      :else
      {:error true
       :message (format "File read not supported for `%s` with mime-type `%s`"
                        (str path)
                        (str (file-content/mime-type path)))})))

(defn format-clojure-view
  "Formats Clojure file view with markdown and usage advice."
  [content path pattern-info]
  (let [{:keys [name-pattern content-pattern match-count total-forms expanded-forms collapsed-forms]} pattern-info
        pattern-text (cond
                       (and name-pattern content-pattern)
                       (str "name_pattern: \"" name-pattern "\" and content_pattern: \"" content-pattern "\"")
                       name-pattern
                       (str "name_pattern: \"" name-pattern "\"")
                       content-pattern
                       (str "content_pattern: \"" content-pattern "\"")
                       :else
                       "no patterns")
        form-stats (when (and total-forms expanded-forms collapsed-forms)
                     (str " (" expanded-forms " expanded, " collapsed-forms " collapsed)"))
        preamble (str "# THIS IS A COLLAPSED VIEW " path "\n"
                      "Set `collapsed: false` to view the entire file\n"
                      (when (or name-pattern content-pattern)
                        (str "Matching " pattern-text form-stats "\n"))
                      "*** `" path "`\n")

        usage-tips (str "\n\n## `read_file` Tool Usage Tips\n\n"
                        "- Use `name_pattern` with regex to match function names (e.g., \"validate.*\")\n"
                        "- Use `content_pattern` to find code containing specific text (e.g., \"try|catch\")\n"
                        "- For defmethod forms: Include the dispatch value (e.g., \"area :rectangle\" or \"dispatch-with-vector \\[:feet :inches\\]\")\n"
                        "- For namespaced methods: Include namespace (e.g., \"tool-system/validate-inputs :clojure-eval\")\n"
                        "- For spec forms: Pattern match on keywords (e.g., \"::user\" or \":domain/user\")\n"
                        "- Reader conditionals display with platform syntax: #?(:clj ...)\n"
                        "- Set `collapsed: false` to view the entire file\n")]

    [(str preamble "```clojure\n" content "\n```" usage-tips)]))

;; Formatter helper functions for different content types

(defn format-raw-file
  "Formats raw file content with markdown."
  [result max-lines]
  (let [{:keys [content path size line-count offset truncated? line-lengths-truncated? total-line-count]} result
        file-type (last (str/split path #"\."))
        lang-hint (when file-type (str file-type))
        preamble (str "### " path "\n"
                      (when truncated?
                        (str "File truncated (showing " line-count " of " total-line-count " lines)\n\n")))]
    [(str preamble "```" lang-hint "\n" content "\n```")]))

(defn format-text-collapsed-view
  "Formats text file collapsed view with markdown."
  [content path pattern-info]
  (let [{:keys [pattern match-count total-lines blocks-count]} pattern-info
        preamble (str "# COLLAPSED VIEW: " path "\n"
                      "Pattern: \"" pattern "\"\n"
                      "Found " match-count " matches in " total-lines " lines"
                      (when blocks-count (str ", showing " blocks-count " block(s)")) "\n"
                      "*** `" path "`\n")]
    [(str preamble "```\n" content "\n```")]))

(defmethod tool-system/format-results :unified-read-file [{:keys [max-lines]} result]
  (if (:error result)
    {:result [(or (:message result) "Unknown error")]
     :error true}
    (case (:mode result)
      :clojure
      {:result (format-clojure-view (:content result)
                                    (:path result)
                                    (:pattern-info result))
       :error false}

      :text-collapsed
      {:result (format-text-collapsed-view (:content result)
                                           (:path result)
                                           (:pattern-info result))
       :error false}

      :raw
      {:result (format-raw-file result max-lines)
       :error false}
      :file-response
      {:result [result]
       :error false}
      {:result ["Unknown result mode"]
       :error true})))

;; Function to register the tool that returns the registration map
(defn unified-read-file-tool
  "Returns the registration map for the unified-read-file tool."
  ([nrepl-client-atom]
   (unified-read-file-tool nrepl-client-atom {}))
  ([nrepl-client-atom opts]
   (tool-system/registration-map (create-unified-read-file-tool nrepl-client-atom opts))))

(comment

  (let [path "/Users/bruce/workspace/llempty/clojure-mcp/src/clojure_mcp/tools/form_edit/tool.clj"
        path2 "NEXT_STEPS.md"
        user-dir (System/getProperty "user.dir")
        tool (unified-read-file-tool (atom {:clojure-mcp.core/nrepl-user-dir user-dir
                                            :clojure-mcp.core/allowed-directories [user-dir]}))
        tool-fn (:tool-fn tool)]
    (tap> (pr-str (tool-fn nil {:path path2 :name_pattern "validates"} (fn [a b] [a b]))))))
