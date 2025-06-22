(ns clojure-mcp.tools.form-edit.core
  "Core utility functions for form editing operations.
   This namespace contains the pure functionality for manipulating Clojure forms
   without any MCP-specific code."
  (:require
   [rewrite-clj.zip :as z]
   [rewrite-clj.parser :as p]
   [rewrite-clj.node :as n]
   [rewrite-clj.paredit :as par]
   [cljfmt.core :as fmt]
   [cljfmt.config :as cljfmt-config]
   [clojure-mcp.config :as config]
   [clojure.string :as str]
   [clojure.java.io :as io]))

;; Form identification and location functions

(defn get-node-string
  "Extract the string value from a node, handling metadata nodes.
   Returns the trimmed string value."
  [zloc]
  (when zloc
    (let [node (z/node zloc)
          tag (n/tag node)]
      (str/trim
       (if (= tag :meta)
         (some-> zloc z/down z/right z/node n/string)
         (n/string node))))))

(defn parse-form-name
  "Parse a form name string into [method-name dispatch-value] parts.
   Normalizes whitespace in the process."
  [form-name]
  (if (string? form-name)
    (let [normalized (-> form-name
                         str/trim
                         (str/replace #"\s+" " "))
          parts (str/split normalized #"\s+" 2)]
      [(first parts) (second parts)])
    [form-name nil]))

(defn method-name-matches?
  "Check if the method name from the zipper matches the expected name."
  [method-elem expected-name]
  (= (get-node-string method-elem) expected-name))

(defn dispatch-value-matches?
  "Check if the dispatch value from the zipper matches the expected dispatch."
  [dispatch-elem expected-dispatch]
  (when (and dispatch-elem expected-dispatch)
    (= (get-node-string dispatch-elem) expected-dispatch)))

(defn check-tag
  "Check if the first element matches the expected tag."
  [first-elem tag]
  (when (= (str/trim (n/string (z/node first-elem))) tag)
    first-elem))

(defn check-method-and-dispatch
  "Check if method name and optionally dispatch value match the expected patterns."
  [method-elem expected-name expected-dispatch]
  (when (method-name-matches? method-elem expected-name)
    (if expected-dispatch
      (some-> method-elem z/right (dispatch-value-matches? expected-dispatch))
      true)))

(defn is-top-level-form?
  "Check if a form matches the given tag and name pattern.
   Handles metadata and complex dispatch values.
   
   This function uses direct zipper navigation and string comparison rather than 
   sexpr conversion, enabling it to properly handle namespaced keywords (::keyword) 
   and complex dispatch values (vectors, maps, qualified symbols/keywords).
   
   For defmethod forms, the name can be either:
   - Just the method name (e.g., 'area') - will match ANY defmethod with that name
   - A compound name with dispatch value (e.g., 'area :rectangle' or 'tool-system/validate-inputs ::tool') 
     - will match only that specific implementation
   
   Arguments:
   - zloc: The zipper location to check
   - tag: The definition tag (e.g., 'defn', 'def', 'defmethod')
   - dname: The name of the definition, which can include dispatch value for defmethod
   
   Returns true if the form matches the pattern."
  [zloc tag dname]
  (try
    (some-> zloc
            z/down
            (check-tag tag)
            z/right
            (#(let [[expected-name expected-dispatch] (parse-form-name dname)]
                (check-method-and-dispatch % expected-name expected-dispatch))))
    (catch Exception _
      ;; Silent error handling in production - use logging in debug mode
      ;; Don't use println as it can interfere with stdin/stdout in server context
      false)))

(defn find-top-level-form
  "Find a top-level form with a specific tag and name in a zipper.
   
   Arguments:
   - zloc: The zipper location to start searching from
   - tag: The tag name as a string (e.g., \"defn\", \"def\", \"ns\")
   - dname: The name of the definition as a string
   - max-depth: Optional maximum depth to search (defaults to 0 for backward compatibility)
                0 = only immediate siblings, 1 = one level deeper, etc.
   
   Returns a map with:
   - :zloc - the zipper location of the matched form, or nil if not found
   - :similar-matches - a vector of maps with {:form-name, :qualified-name, :tag} for potential namespace-qualified matches"
  ([zloc tag dname] (find-top-level-form zloc tag dname 0))
  ([zloc tag dname max-depth]
   (let [similar-matches (atom [])
         queue (atom [[zloc 0]])] ; [location, depth] pairs

     (letfn [(collect-similar-match [loc]
               ;; Check for namespace-qualified form with matching unqualified name
               (try
                 (let [sexpr (z/sexpr loc)]
                   (when (and (list? sexpr) (> (count sexpr) 1))
                     (let [form-tag (first sexpr)
                           form-name (second sexpr)]
                       ;; Check for forms where the tag's unqualified name matches our tag
                       (when (and (symbol? form-tag)
                                  (symbol? form-name)
                                  (= (name form-tag) tag) ;; Tag's name part matches our tag
                                  (= (name form-name) dname)) ;; Form name's name part matches our name
                         (swap! similar-matches conj
                                {:form-name dname
                                 :qualified-name form-name
                                 :tag form-tag})))))
                 (catch Exception _ nil)))]

       (loop []
         (if-let [[current-loc current-depth] (first @queue)]
           (do
             (swap! queue rest) ; remove first item from queue

             (cond
               ;; Found our target form
               (is-top-level-form? current-loc tag dname)
               {:zloc current-loc :similar-matches @similar-matches}

               ;; Continue searching
               :else
               (do
                 (collect-similar-match current-loc)

                 ;; Add right sibling at same depth to queue
                 (when-let [right-sibling (z/right current-loc)]
                   (swap! queue conj [right-sibling current-depth]))

                 ;; Add ALL children at next depth if within limit
                 (when (< current-depth max-depth)
                   (loop [child (z/down current-loc)]
                     (when child
                       (swap! queue conj [child (inc current-depth)])
                       (recur (z/right child)))))

                 (recur))))

           ;; Queue empty, form not found
           {:zloc nil :similar-matches @similar-matches}))))))

;; Form editing operations

(defn edit-top-level-form
  "Edit a top-level form by replacing it or inserting content before or after.
   
   Arguments:
   - zloc: The zipper location to start searching from
   - tag: The form type (e.g., 'defn, 'def, 'ns)
   - name: The name of the form
   - content-str: The string to insert or replace with (can contain multiple forms)
   - edit-type: Keyword indicating the edit type (:replace, :before, or :after)
   - max-depth: Optional maximum depth to search (defaults to 0 for backward compatibility)
   
   Returns a map with:
   - :zloc - the updated zipper (or nil if form not found)
   - :similar-matches - a vector of potential namespace-qualified matches"
  ([zloc tag name content-str edit-type] (edit-top-level-form zloc tag name content-str edit-type 3))
  ([zloc tag name content-str edit-type max-depth]
   (let [find-result (find-top-level-form zloc tag name max-depth)
         form-zloc (:zloc find-result)]
     (if-not form-zloc
       find-result ;; Return the result with nil :zloc and any similar-matches
       (let [updated-zloc
             (case edit-type
               :replace (z/replace form-zloc (p/parse-string-all content-str))
               ;; it would be nice if this handled comments immediately preceeding the form
               :before (-> form-zloc
                           (z/insert-left (p/parse-string-all "\n\n"))
                           z/left
                           (z/insert-left (p/parse-string-all content-str))
                           z/left)
               :after (-> form-zloc
                          (z/insert-right (p/parse-string-all "\n\n"))
                          z/right
                          (z/insert-right (p/parse-string-all content-str))
                          z/right))]
         {:zloc updated-zloc
          :similar-matches (:similar-matches find-result)})))))

;; Offset calculation functions for highlighting modified code

(defn row-col->offset
  "Convert row and column coordinates to a character offset in a string.
   
   Arguments:
   - s: The source string
   - target-row: The target row (1-based)
   - target-col: The target column (1-based)
   
   Returns the character offset in the string."
  [s target-row target-col]
  (loop [lines (str/split-lines s)
         current-row 1
         offset 0]
    (if (or (empty? lines) (>= current-row target-row))
      (+ offset target-col) ; Add col for 1-based index
      (recur (next lines)
             (inc current-row)
             (+ offset (count (first lines)) 1)))))

(defn zloc-offsets
  "Calculate character offsets for a zipper location's start and end positions.
   
   Arguments:
   - source-str: The source code string
   - positions: A vector of [row col] pairs
   
   Returns a vector of character offsets."
  [source-str positions]
  (mapv (fn [[row col]] (row-col->offset source-str row col))
        positions))

;; Functions for working with docstrings

(defn find-docstring
  "Finds the docstring node of a top-level form.
   
   Arguments:
   - zloc: The zipper location to start searching from
   - tag: The form type (e.g., 'defn, 'def)
   - name: The name of the form
   
   Returns a map with:
   - :zloc - the zipper positioned at the docstring node (or nil if not found)
   - :similar-matches - a vector of potential namespace-qualified matches from find-top-level-form"
  [zloc tag name]
  (let [find-result (find-top-level-form zloc tag name)
        form-zloc (:zloc find-result)]
    (if-not form-zloc
      find-result ;; Return the result with nil :zloc and any similar-matches
      (let [tag-zloc (z/down form-zloc) ;; Move to the tag (defn, def, etc.)
            name-zloc (z/right tag-zloc) ;; Move to the name
            docstring-candidate (z/right name-zloc) ;; Move to potential docstring
            docstring-zloc (when (and docstring-candidate
                                   ;; Check for both :token (single-line) and :multi-line (multi-line) tags
                                      (contains? #{:token :multi-line} (z/tag docstring-candidate))
                                      (string? (z/sexpr docstring-candidate)))
                             docstring-candidate)]
        {:zloc docstring-zloc
         :similar-matches (:similar-matches find-result)}))))

(defn edit-docstring
  "Edit a docstring in a top-level form.
   
   Arguments:
   - zloc: The zipper location to start searching from
   - tag: The form type (e.g., 'defn, 'def)
   - name: The name of the form
   - new-docstring: The new docstring content
   
   Returns a map with:
   - :zloc - the updated zipper (or nil if form/docstring not found)
   - :similar-matches - a vector of potential namespace-qualified matches"
  [zloc tag name new-docstring]
  (let [docstring-result (find-docstring zloc tag name)
        docstring-zloc (:zloc docstring-result)]
    (if docstring-zloc
      ;; Found the docstring, update it
      {:zloc (z/replace docstring-zloc (n/string-node new-docstring))
       :similar-matches (:similar-matches docstring-result)}
      ;; Couldn't find docstring
      docstring-result)))

;; Functions for working with comments

(defn is-comment-form?
  "Check if a zloc is a (comment ...) form.
   
   Arguments:
   - zloc: The zipper location to check
   
   Returns true if the form is a comment form."
  [zloc]
  (try
    (and (z/seq? zloc)
         (let [first-child (z/down zloc)]
           (and first-child
                (= (z/sexpr first-child) 'comment))))
    (catch Exception _ false)))

(defn is-line-comment?
  "Check if a zloc is a line comment.
   
   Arguments:
   - zloc: The zipper location to check
   
   Returns true if the node is a line comment."
  [zloc]
  (try
    (= (-> zloc z/node n/tag) :comment)
    (catch Exception _ false)))

(defn find-comment-block
  "Find a comment block (either a 'comment' form or consecutive comment lines)
   that contains a specific substring.
   
   This version properly handles comment blocks at the end of the file.
   
   Arguments:
   - source: The source string to search in
   - comment-substring: The substring to look for
   
   Returns a map with :type, :start, :end, and :content keys,
   or nil if no matching comment block is found."
  [source comment-substring]
  (let [zloc (z/of-string source {:track-position? true})
        lines (str/split-lines source)]

    ;; First, try to find a comment form
    (loop [loc zloc]
      (cond
        ;; No more forms
        (nil? loc)
        (let [;; Process line by line to find comment blocks
              result (reduce
                      (fn [[blocks current-block] [idx line]]
                        (cond
                         ;; If we're already tracking a block and the line is a comment
                          (and current-block
                               (str/starts-with? (str/trim line) ";;"))
                          [blocks (update current-block :lines conj line)]

                         ;; If we're tracking a block and hit a non-comment line
                          current-block
                          [(conj blocks (assoc current-block :end (dec idx))) nil]

                         ;; If this is a new comment line
                          (str/starts-with? (str/trim line) ";;")
                          [blocks {:start idx
                                   :lines [line]}]

                         ;; Otherwise, continue
                          :else
                          [blocks nil]))
                      [[] nil]
                      (map-indexed vector lines))

              ;; Extract the blocks and potential unfinished block
              [blocks current-block] result

              ;; Finalize blocks, including any unfinished block at EOF
              final-blocks (if current-block
                             (conj blocks (assoc current-block
                                                 :end (+ (:start current-block)
                                                         (dec (count (:lines current-block))))))
                             blocks)

              ;; Find the first consecutive comment block containing the substring
              matching-block (first (filter #(some (fn [line]
                                                     (str/includes? line comment-substring))
                                                   (:lines %))
                                            final-blocks))]

          (when matching-block
            {:type :line-comments
             :start (:start matching-block)
             :end (:end matching-block)
             :content (str/join "\n" (:lines matching-block))}))

        ;; Check if current form is a comment form
        (is-comment-form? loc)
        (let [comment-str (z/string loc)]
          (if (str/includes? comment-str comment-substring)
            (let [pos (z/position-span loc)]
              {:type :comment-form
               :start (first pos) ;; [row col]
               :end (second pos) ;; [row col]
               :content comment-str
               :zloc loc})
            (recur (z/right loc))))

        ;; Move to the next form
        :else (recur (z/right loc))))))

(defn edit-comment-block
  "Edit a comment block in the source code.
   
   Arguments:
   - source: The source string
   - comment-substring: Substring to identify the comment block
   - new-content: New content to replace the comment block with
   
   Returns the updated source code string, or the original if no matching block was found."
  [source comment-substring new-content]
  (let [block (find-comment-block source comment-substring)]
    (if (nil? block)
      source ;; No matching block found
      (let [lines (str/split-lines source)]
        (case (:type block)
          ;; For comment forms, use zloc to replace
          :comment-form
          (-> (:zloc block)
              (z/replace (p/parse-string new-content))
              z/root-string)

          ;; For line comments, replace the relevant lines
          :line-comments
          (let [start (:start block)
                end (:end block)
                new-lines (str/split-lines new-content)
                result (concat
                        (take start lines)
                        new-lines
                        (drop (inc end) lines))]
            (str/join "\n" result)))))))

;; Form summary and visualization functions

;; Forward declaration for extract-form-name to enable better organization
(declare extract-form-name)

(defn get-form-summary
  "Get a summarized representation of a Clojure form showing only up to the argument list.
   
   Arguments:
   - zloc: The zipper location of the form
   
   Returns a string representation of the form summary, or nil if not a valid form."
  [zloc]
  (try
    (let [sexpr (z/sexpr zloc)]
      (when (and (seq? sexpr) (symbol? (first sexpr)))
        (let [form-type (name (first sexpr))
              form-name (extract-form-name sexpr)]

          (case form-type
            "defn" (let [zloc-down (z/down zloc) ; Move to the symbol "defn"
                         name-loc (and zloc-down (z/right zloc-down)) ; Move to name
                         maybe-docstring (and name-loc (z/right name-loc)) ; Next node after name
                         args-loc (if (and maybe-docstring
                                           (contains? #{:token :multi-line} (z/tag maybe-docstring))
                                           (string? (z/sexpr maybe-docstring)))
                                    (z/right maybe-docstring) ; Skip docstring to find args
                                    maybe-docstring)] ; No docstring, args right after name
                     (if (and args-loc (= (z/tag args-loc) :vector))
                       (str "(defn " form-name " " (z/string args-loc) " ...)")
                       (str "(defn " form-name " [...] ...)")))

            "defmacro" (let [zloc-down (z/down zloc) ; Move to the symbol "defmacro"
                             name-loc (and zloc-down (z/right zloc-down)) ; Move to name
                             maybe-docstring (and name-loc (z/right name-loc)) ; Next node after name
                             args-loc (if (and maybe-docstring
                                               (contains? #{:token :multi-line} (z/tag maybe-docstring))
                                               (string? (z/sexpr maybe-docstring)))
                                        (z/right maybe-docstring) ; Skip docstring to find args
                                        maybe-docstring)] ; No docstring, args right after name
                         (if (and args-loc (= (z/tag args-loc) :vector))
                           (str "(defmacro " form-name " " (z/string args-loc) " ...)")
                           (str "(defmacro " form-name " [...] ...)")))

            "defmethod" (let [zloc-down (z/down zloc) ; Move to the symbol "defmethod"
                              method-loc (and zloc-down (z/right zloc-down)) ; Move to method name
                              method-sym (and method-loc (z/sexpr method-loc))
                              method-name (if (symbol? method-sym)
                                            (if (namespace method-sym)
                                              (str (namespace method-sym) "/" (name method-sym))
                                              (name method-sym))
                                            "unknown")
                              dispatch-loc (and method-loc (z/right method-loc)) ; Move to dispatch value
                              dispatch-val (and dispatch-loc (z/sexpr dispatch-loc))
                              dispatch-str (and dispatch-val (pr-str dispatch-val))
                              ;; Find the argument vector after the dispatch value
                              args-loc (loop [loc (and dispatch-loc (z/right dispatch-loc))]
                                         (cond
                                           (nil? loc) nil
                                           (= (z/tag loc) :vector) loc
                                           :else (recur (z/right loc))))]
                          (if (and args-loc (= (z/tag args-loc) :vector))
                            (str "(defmethod " method-name " " dispatch-str " " (z/string args-loc) " ...)")
                            (str "(defmethod " method-name " " dispatch-str " [...] ...)")))

            "def" (str "(def " form-name " ...)")
            "deftest" (str "(deftest " form-name " ...)")
            "ns" (z/string zloc) ; Always show the full namespace
            (str "(" form-type " " (or form-name "") " ...)")))))
    (catch Exception e
      ;; Provide a fallback in case of errors
      (try
        (let [raw-str (z/string zloc)]
          (if (< (count raw-str) 60)
            raw-str
            (str (subs raw-str 0 57) "...")))
        (catch Exception _
          nil)))))

(defn valid-form-to-include?
  "Check if a form should be included in the collapsed view.
   Excludes forms like comments, unevals, whitespace, etc.
   
   Arguments:
   - zloc: The zipper location to check
   
   Returns:
   - true if the form should be included, false otherwise"
  [zloc]
  (try
    (when zloc
      (let [tag (z/tag zloc)]
        ;; Exclude specific node types we don't want to process
        (not (or
              ;; Skip uneval forms (#_)
              (= tag :uneval)
              ;; Skip whitespace
              (= tag :whitespace)
              ;; Skip newlines
              (= tag :newline)
              ;; Skip comments
              (= tag :comment)))))
    (catch Exception _
      ;; If we can't determine the type, skip it to be safe
      false)))

;; TODO form name can be a keyword if its a spec 
(defn extract-form-name
  "Extract the name of a form from its sexpr representation.
   For example, from (defn foo [x] ...) it extracts 'foo'.
   
   Arguments:
   - sexpr: The S-expression to extract the name from
   
   Returns:
   - The name as a string, or nil if no name could be extracted"
  [sexpr]
  (try
    (when (and (seq? sexpr)
               (> (count sexpr) 1)
               (symbol? (second sexpr)))
      (name (second sexpr)))
    (catch Exception _
      nil)))

;; Source code formatting

(def default-formatting-options
  {:indentation? true
   :remove-surrounding-whitespace? true
   :remove-trailing-whitespace? true
   :insert-missing-whitespace? true
   :remove-consecutive-blank-lines? true
   :remove-multiple-non-indenting-spaces? true
   :split-keypairs-over-multiple-lines? false
   :sort-ns-references? false
   :function-arguments-indentation :community
   :indents fmt/default-indents})

(defn- load-cljfmt-config [nrepl-user-dir]
  (let [path (cljfmt-config/find-config-file nrepl-user-dir)]
    (->> (some-> path cljfmt-config/read-config)
         (merge default-formatting-options)
         (cljfmt-config/convert-legacy-keys))))

(defn project-formatting-options [nrepl-client-map]
  (let [nrepl-user-dir (config/get-nrepl-user-dir nrepl-client-map)]
    (load-cljfmt-config nrepl-user-dir)))

(defn format-source-string
  "Formats a source code string using cljfmt. Use the project-formatting-options
   function to get comprehensive formatting options for the current project.
   
   Arguments:
   - source-str: The source code string to format
   - formatting-options: Options for cljfmt
   
   Returns:
   - The formatted source code string"
  [source-str formatting-options]
  (fmt/reformat-string source-str formatting-options))

;; File operations

(defn load-file-content
  "Loads content from a file.
   
   Arguments:
   - file-path: Path to the file
   
   Returns:
   - The file content as a string, or an error map if the file could not be read"
  [file-path]
  (try
    {:content (slurp file-path)
     :error false}
    (catch java.io.FileNotFoundException _
      {:error true
       :message (str "File not found: " file-path)})
    (catch java.io.IOException e
      {:error true
       :message (str "IO error while reading file: " (.getMessage e))})))

(defn save-file-content
  "Saves content to a file.
   
   Arguments:
   - file-path: Path to the file
   - content: The content to save
   
   Returns:
   - A map with :success true if the file was saved, or :success false and :message if an error occurred"
  [file-path content]
  (try
    (spit file-path content)
    {:success true}
    (catch Exception e
      {:success false
       :message (str "Failed to save file: " (.getMessage e))})))

(defn extract-dispatch-from-defmethod
  "Extracts the method name and dispatch value from defmethod source code.
   Returns [method-name dispatch-value-str] or nil if parsing fails.
   
   Arguments:
   - source-code: The defmethod source code as a string
   
   Returns:
   - A vector of [method-name dispatch-value-str] or nil if parsing fails"
  [source-code]
  (try
    (let [zloc (z/of-string source-code)
          sexp (z/sexpr zloc)]
      (when (and (list? sexp)
                 (= (first sexp) 'defmethod)
                 (>= (count sexp) 3))
        (let [method-name (name (second sexp))
              dispatch-value (nth sexp 2)
              dispatch-str (pr-str dispatch-value)]
          [method-name dispatch-str])))
    (catch Exception _ nil)))

;; multi sexp editing

(defn semantic-nodes?
  "Returns true if node contributes to program semantics"
  [node]
  (not (#{:comment :uneval :whitespace :newline :comma} (n/tag node))))

(defn normalize-whitespace-node
  "Normalize whitespace within a node while preserving structure"
  [node]
  (if (= :forms (n/tag node))
    (n/forms-node (map normalize-whitespace-node (n/children node)))
    (if (n/inner? node)
      (let [children (n/children node)
            filtered (->> children
                          (remove #(#{:whitespace :newline :comma} (n/tag %)))
                          (map normalize-whitespace-node)
                          (interpose (n/spaces 1))
                          vec)]
        (n/replace-children node filtered))
      node)))

(defn normalize-and-clean-node
  "Normalize whitespace and remove non-semantic forms"
  [node]
  (cond
    ;; Skip non-semantic nodes entirely
    (not (semantic-nodes? node)) nil

    ;; For forms node, recursively process children
    (= :forms (n/tag node))
    (n/forms-node (->> (n/children node)
                       (map normalize-and-clean-node)
                       (filter some?)))

    ;; For other container nodes
    (n/inner? node)
    (let [children (n/children node)
          cleaned (->> children
                       (map normalize-and-clean-node)
                       (filter some?)
                       (interpose (n/spaces 1))
                       vec)]
      (n/replace-children node cleaned))

    ;; Leaf nodes pass through
    :else node))

(def ^:dynamic *match-clean* false)

(defn zchild-match-exprs
  "Extract expressions for pattern matching.

   Normalizes whitespace within semantic forms.

   By default, including comments and #_ forms. Preserves accuracy by
   including non-semantic nodes in sequence. Set clean? to true if you want to ignore
   comments in the match.
   
   Options:
   - :clean? (default false) 
   
   Example:
   (zchild-match-exprs (z/of-string* \";; TODO\\n(defn foo [x] x)\"))
   => (\";; TODO\\n\" \"(defn foo [x] x)\")"
  ([zloc]
   (zchild-match-exprs zloc {:clean? *match-clean*}))
  ([zloc {:keys [clean?] :or {clean? false}}]
   (let [nodes (if (= :forms (z/tag zloc))
                 ;; If at forms node, get children
                 (n/children (z/node zloc))
                 ;; Otherwise iterate through siblings
                 (->> (iterate z/right* zloc)
                      (take-while some?)
                      (map z/node)))]
     (->> nodes
          (filter (fn [node]
                    (let [tag (n/tag node)]
                      (or (semantic-nodes? node)
                          (and (not clean?)
                               (or (= :comment tag)
                                   (= :uneval tag)))))))
          (map (fn [node]
                 (if (semantic-nodes? node)
                   (-> ((if clean?
                          normalize-and-clean-node
                          normalize-whitespace-node)
                        node)
                       n/string)
                   (n/string node))))))))

(defn str-forms->sexps [str-forms]
  (zchild-match-exprs (z/of-node (p/parse-string-all str-forms))))

(defn match-multi-sexp [match-sexprs zloc]
  (let [len (count match-sexprs)
        zloc-sexprs (zchild-match-exprs zloc)
        matched (map = match-sexprs zloc-sexprs)]
    (and (every? identity matched)
         (= (count matched) len))))

(defn iterate-to-n [f x n]
  (->> (iterate f x)
       (take n)
       last))

(defn kill-n-sexps [n zloc]
  ;; zloc on first item to remove
  ;; calling z/next after this should get you to the next position
  (-> zloc
      (z/insert-left (p/parse-string "(__clojure-mcp-edit-marker__)"))
      z/prev ;; inside added node
      ;; then slurp into the node
      (as-> z (nth (iterate par/slurp-forward z) n))
      #_(as-> z (iterate-to-n par/slurp-forward z (inc n)))
      z/up
      z/remove-preserve-newline))

(defn zleft-n [zloc n]
  (iterate-to-n z/left zloc n))

(defn zright-n [zloc n]
  (iterate-to-n z/right zloc n))

(defn count-forms-to-kill [start-zloc match-count]
  (if *match-clean*
    ;; Count non-whitespace forms until we've seen all semantic matches
    (loop [loc start-zloc
           semantic-seen 1
           total-count 1]
      (if (>= semantic-seen match-count)
        total-count
        (if-let [next-loc (z/right loc)] ; z/right skips whitespace
          (let [semantic? (and (z/sexpr-able? next-loc)
                               (semantic-nodes? (z/node next-loc)))]
            (recur next-loc
                   (if semantic? (inc semantic-seen) semantic-seen)
                   (inc total-count)))
          total-count)))
    match-count))

;; TODO probably dont need special handing for empty replacement
(defn replace-multi [zloc match-sexprs replacement-node]
  (let [kill-count (count-forms-to-kill zloc (count match-sexprs))]
    (if (nil? replacement-node)
      (let [after-loc (kill-n-sexps kill-count zloc)]
        {:edit-span-loc after-loc
         :after-loc after-loc})
      (let [after-insert (z/insert-left zloc replacement-node)
            after-loc (->> after-insert ;; could left and splice
                           (kill-n-sexps kill-count))]
        {:edit-span-loc (-> after-insert z/left)
         :after-loc after-loc}))))

(defn insert-before-multi [zloc match-sexprs replacement-node]
  (let [edit-loc (-> (z/insert-left zloc replacement-node)
                     z/left)]
    {:edit-span-loc edit-loc
     :after-loc (-> edit-loc
                    z/splice
                    (zright-n (count (n/child-sexprs replacement-node))))}))

(defn insert-after-multi [zloc match-sexprs replacement-node]
  (let [edit-loc (-> (take (count match-sexprs) (iterate z/right zloc))
                     last
                     (z/insert-right replacement-node)
                     z/right)]
    {:edit-span-loc edit-loc
     :after-loc (-> edit-loc
                    z/splice
                    (zright-n (count (n/child-sexprs replacement-node))))}))

(defn find-multi-sexp [zloc match-sexprs]
  (->> (iterate z/next zloc)
       (take-while (complement z/end?))
       (filter z/sexpr-able?)
       (filter #(match-multi-sexp match-sexprs %))
       first))

(defn find-and-edit-one-multi-sexp [zloc operation match-form new-form]
  {:pre [(#{:insert-before :insert-after :replace} operation) zloc (string? match-form) (string? new-form)]}
  ;; no-op
  (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
    (let [new-node (when-not (str/blank? new-form) (p/parse-string-all new-form))
          match-sexprs (str-forms->sexps match-form)]
      (when-let [found-loc (find-multi-sexp zloc match-sexprs)]
        (condp = operation
          :insert-before (insert-before-multi found-loc match-sexprs new-node)
          :insert-after (insert-after-multi found-loc match-sexprs new-node)
          (replace-multi found-loc match-sexprs new-node))))))

(defn find-and-edit-all-multi-sexp [zloc operation match-form new-form]
  {:pre [(#{:insert-before :insert-after :replace} operation) zloc (string? match-form) (string? new-form)]}
  (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
    (loop [loc zloc
           locations []]
      (if-let [{:keys [after-loc edit-span-loc]}
               (find-and-edit-one-multi-sexp loc operation match-form new-form)]
        (recur after-loc (conj locations edit-span-loc))
        (when-not (empty? locations)
          ;; this is a location after the last match
          ;; z/root-string on this will produce the final edited form
          {:zloc loc
           :locations locations})))))

(defn find-and-edit-multi-sexp* [zloc match-form new-form {:keys [operation all?]}]
  (if all?
    (find-and-edit-all-multi-sexp zloc operation match-form new-form)
    (when-let [{:keys [after-loc edit-span-loc]} (find-and-edit-one-multi-sexp zloc operation match-form new-form)]
      ;; this is a location after the last match
      ;; z/root-string on this will produce the final edited form
      {:zloc after-loc
       :locations [edit-span-loc]})))

(defn find-and-edit-multi-sexp [zloc match-form new-form opts]
  (or (find-and-edit-multi-sexp* zloc match-form new-form opts)
      (binding [*match-clean* true]
        (find-and-edit-multi-sexp* zloc match-form new-form opts))))

(comment

  (let [source "(defn test-fn [x] (+ x 1) (+ x 2))"
        zloc (z/of-string source)
        result (find-and-edit-multi-sexp zloc "(+ x 1) (+ x 2)" "(inc x)" {:operation :replace})
        updated (z/root-string (:zloc result))]
    updated)

  (-> (find-and-edit-multi-sexp
       (z/of-string "(defn test-fn [x] (+ x 1) (+ x 2))")
       "(+ x 1) (+ x 2)"
       "(inc x) (+ x 10)"
       {:operation :replace})
      :zloc
      z/root-string)

  (-> (z/of-string "(+ x 1) (+ x 2)"))

  (let [new-node (p/parse-string-all "(inc x) (+ x 10)")]
    (-> (find-multi-sexp
         (z/of-string "(defn test-fn [x] (+ x 1) (+ x 2))")
         (str-forms->sexps "(+ x 1) (+ x 2)"))
        (z/insert-left new-node)
        ;;(z/insert-left (p/parse-string "(:edit-marker)"))
        ;;z/prev
        ;;(par/slurp-forward)
        ;;(par/slurp-forward)
        ;;z/up
        ;;z/remove
        #_(z/subedit->
           (z/replace (p/parse-string "x")))
        ;; z/remove
        #_(replace-multi
           (str-forms->sexps "(+ x 1) (+ x 2)"))

        z/root-string))

  (def test-content (str "(ns test.core)\n\n"
                         "(defn example-fn [x y]\n"
                         "  #_(println \"debug value:\" x)\n"
                         "  (+ x y)\n"
                         "  (+ x 1)\n"
                         "  (- y 1))\n\n"
                         "(defn another-fn [z]\n"
                         "  (+ z 1)\n"
                         "  (let [result (+ z 1)]\n"
                         "    (* result 2)))\n\n"
                         "(defn process-map [m]\n"
                         "  (map #(* % 2) (vals m)))\n\n"
                         "(def config {:key1 100 :key2 200})\n\n"
                         "(comment\n"
                         "  (example-fn 1 2)\n"
                         "  (+ 1 2)\n"
                         "  (println \"testing\"))\n\n"
                         ";; Test comment\n;; spans multiple lines"))

  (def debug-zloc (z/of-node (p/parse-string-all test-content)))

  (-> (find-and-edit-multi-sexp
       debug-zloc
       "(+ x y)"
       "(+ xxx yyx)"
       {:operation :replace
        :all? false})
      :zloc
      z/root-string
      println)

  (-> (find-and-edit-multi-sexp
       (z/of-string "#_1 2")
       "2"
       "3"
       {:operation :replace
        :all? false})
      :zloc
      z/root-string
      #_println)

  (-> (find-and-edit-multi-sexp
       (z/of-node (p/parse-string-all "[1 2 3 4 5]"))
       "1 2 3 4 5 "
       "a b c d e"
       {:operation :insert-after})
      :zloc
      z/root-string)

  (-> (find-and-edit-multi-sexp
       (z/of-node (p/parse-string-all "[1 2 3 4 5]"))
       "1 2 3 4 5 "
       "a b c d e"
       {:operation :insert-before})
      :zloc
      z/root-string)

  (-> (find-and-edit-multi-sexp
       (z/of-node (p/parse-string-all "[1 2 3 4 5]"))
       "1 2 3 4 5 "
       "a b c d e"
       {:operation :replace})
      :zloc
      z/root-string)

  (-> (find-and-edit-multi-sexp
       (z/of-node (p/parse-string-all "[1 2 3 4 5]"))
       "1"
       "a"
       {:operation :replace})
      :zloc
      z/root-string)

  (z/root-string (:zloc (find-and-edit-multi-sexp
                         (z/of-node (p/parse-string-all "[1 2 3 4 5]"))
                         "1 2 3 4 5 "
                         "a b c d e"
                         {:operation :replace})))

  (-> (find-and-edit-multi-sexp
       (z/of-node (p/parse-string-all "[a a a a a a a]"))
       "a a"
       ""
       {:operation :replace
        :replace-all true})
      #_:locations
      :zloc
      z/root-string))
