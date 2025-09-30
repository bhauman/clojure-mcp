(ns clojure-mcp.tools.unified-read-file.core
  "Core implementation for the read-file tool.
   This namespace contains the pure functionality without any MCP-specific code."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn find-matching-line-indices
  "Find all line indices that match the given pattern."
  [lines pattern]
  (when pattern
    (keep-indexed
     (fn [idx line]
       (when (re-find pattern line)
         idx))
     lines)))

(defn create-block
  "Create a block around a matching line with context."
  [line-idx total-lines context-before context-after]
  {:start (max 0 (- line-idx context-before))
   :end (min (dec total-lines) (+ line-idx context-after))
   :match-lines [line-idx]})

(defn merge-two-blocks
  "Merge two overlapping blocks into one."
  [block1 block2]
  {:start (:start block1)
   :end (:end block2)
   :match-lines (vec (concat (:match-lines block1) (:match-lines block2)))})

(defn overlapping?
  "Check if two blocks overlap or are within merge-threshold lines of each other."
  [block1 block2 merge-threshold]
  (>= (+ (:end block1) merge-threshold) (:start block2)))

(defn merge-overlapping-blocks
  "Merge blocks that overlap or are within merge-threshold lines of each other."
  [blocks merge-threshold]
  (reduce
   (fn [merged-blocks block]
     (if (empty? merged-blocks)
       [block]
       (let [last-block (last merged-blocks)]
         (if (overlapping? last-block block merge-threshold)
           (conj (vec (butlast merged-blocks))
                 (merge-two-blocks last-block block))
           (conj merged-blocks block)))))
   []
   (sort-by :start blocks)))

(defn format-block
  "Format a single block with line numbers and match indicators."
  [lines block]
  (let [block-lines (subvec (vec lines) (:start block) (inc (:end block)))
        match-set (set (:match-lines block))]
    (map-indexed
     (fn [idx line]
       (let [line-num (+ (:start block) idx)]
         {:line-num line-num
          :content line
          :is-match (contains? match-set line-num)}))
     block-lines)))

(defn generate-text-collapsed-view
  "Generate a collapsed view of a text file based on pattern matches."
  [content pattern-str context-before context-after]
  (if (str/blank? pattern-str)
    {:error true
     :message "Pattern is required for collapsed view of non-Clojure files"}
    (try
      (let [pattern (re-pattern pattern-str)
            lines (str/split-lines content)
            matching-indices (find-matching-line-indices lines pattern)]
        (if (empty? matching-indices)
          {:view (str "No matches found for pattern: " pattern-str)
           :pattern-info {:pattern pattern-str
                          :match-count 0
                          :total-lines (count lines)}}
          (let [blocks (map #(create-block % (count lines) context-before context-after)
                            matching-indices)
                merged-blocks (merge-overlapping-blocks blocks 20)
                formatted-blocks (map #(format-block lines %) merged-blocks)]
            {:view (str/join
                    "\n...\n"
                    (map (fn [block]
                           (str/join "\n"
                                     (map (fn [{:keys [line-num content is-match]}]
                                            (format "%4d%s %s"
                                                    (inc line-num)
                                                    (if is-match " >" "  ")
                                                    content))
                                          block)))
                         formatted-blocks))
             :pattern-info {:pattern pattern-str
                            :match-count (count matching-indices)
                            :total-lines (count lines)
                            :blocks-count (count merged-blocks)}})))
      (catch Exception e
        {:error true
         :message (str "Error processing pattern: " (.getMessage e))}))))

(defn read-file
  "Reads a file from the filesystem, with optional line offset and limit.
   
   Parameters:
   - path: The validated and normalized path to the file
   - offset: Line number to start reading from (0-indexed, default 0)
   - limit: Maximum number of lines to read (default 2000)
   - max-line-length: Maximum length per line before truncation (default 1000)
   
   Returns a map with:
   - :content - The file contents as a string
   - :path - The absolute path to the file
   - :truncated? - Whether the file was truncated due to line limit
   - :truncated-by - The reason for truncation (e.g., 'max-lines')
   - :size - The file size in bytes
   - :line-count - The number of lines returned
   - :total-line-count - The total number of lines in the file (only present when truncated)
   - :offset - The line offset used
   - :max-line-length - The max line length used
   - :line-lengths-truncated? - Whether any lines were truncated in length
   
   If the file doesn't exist or cannot be read, returns a map with :error key"
  [path offset limit & {:keys [max-line-length] :or {max-line-length 1000}}]
  (let [file (io/file path)]
    (if (.exists file)
      (if (.isFile file)
        (try
          (if (and (nil? limit) (zero? offset) (nil? max-line-length))
            ;; Simple case - just read the whole file
            {:content (slurp file)
             :path (.getAbsolutePath file)
             :truncated? false}
            ;; Complex case with limits
            (let [size (.length file)
                  lines (with-open [rdr (io/reader file)]
                          (doall
                           (cond->> (drop offset (line-seq rdr))
                             limit (take (inc limit))
                             true (map
                                   (fn [line]
                                     (if (and max-line-length (> (count line) max-line-length))
                                       (str (subs line 0 max-line-length) "...")
                                       line))))))
                  truncated-by-lines? (and limit (> (count lines) limit))
                  ;; If truncated, count total lines in file for accurate message
                  total-line-count (when truncated-by-lines?
                                     (with-open [rdr (io/reader file)]
                                       (count (line-seq rdr))))
                  content-lines (if truncated-by-lines? (take limit lines) lines)
                  content (str/join "\n" content-lines)]
              {:content content
               :path (.getAbsolutePath file)
               :truncated? truncated-by-lines?
               :truncated-by (when truncated-by-lines? "max-lines")
               :size size
               :line-count (count content-lines)
               :total-line-count total-line-count
               :offset offset
               :max-line-length max-line-length
               :line-lengths-truncated? (and max-line-length
                                             (some #(.contains % "...") lines))}))
          (catch Exception e
            {:error (str "Error reading file: " (.getMessage e))}))
        {:error (str path " is not a file")})
      {:error (str path " does not exist")})))