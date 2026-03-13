(ns clojure-mcp.tools.form-edit.core
  "Core utility functions for form editing operations.
   This namespace contains the pure functionality for manipulating Clojure forms
   without any MCP-specific code."
  (:require
   [cljfmt.config :as cljfmt-config]
   [cljfmt.core :as fmt]
   [clojure-mcp.config :as config]
   [clojure.string :as str]
   [rewrite-clj.node :as n]
   [rewrite-clj.parser :as p]
   [rewrite-clj.zip :as z]
   [clojure-mcp.utils.file :as file-utils]
   [taoensso.timbre :as log]))

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

(defn tag-match?
  "Determine if the actual tag matches the expected tag.

  Matches both public and private definition forms, allowing `def` to match
  `def-` and `defn` to match `defn-`."
  [expected actual]
  (or (= actual expected)
      (= actual (str expected "-"))))

(defn check-tag
  "Check if the first element matches the expected tag."
  [first-elem tag]
  (let [actual (str/trim (n/string (z/node first-elem)))]
    (when (tag-match? tag actual)
      first-elem)))

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
                                  (tag-match? tag (name form-tag)) ;; Tag matches expected (public or private)
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

(defn walk-back-to-non-comment [zloc]
  (z/find-next zloc z/prev*
               (fn [zloc]
                 (not (#{:whitespace :comment} (n/tag (z/node zloc)))))))

(defn remove-consecutive-comments [zloc]
  (if (n/whitespace-or-comment? (z/node zloc))
    (remove-consecutive-comments
     (-> zloc
         z/remove*
         z/next*))
    zloc))

(defn replace-top-level-form
  "function replacement with special handling for leading comments"
  [form-zloc content-str]
  (if (-> content-str str/trim (str/starts-with? ";"))
    (-> form-zloc
        walk-back-to-non-comment
        z/next*
        remove-consecutive-comments
        (z/replace (p/parse-string-all content-str)))
    (z/replace form-zloc (p/parse-string-all content-str))))

(defn insert-before-top-level-form [zloc content-str]
  (-> zloc
      walk-back-to-non-comment
      z/next*
      (z/insert-left* (p/parse-string-all "\n\n"))
      z/left
      (z/insert-left* (p/parse-string-all content-str))
      z/left))

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
               :replace (replace-top-level-form form-zloc content-str)
               :before (insert-before-top-level-form form-zloc content-str)
               :after (-> form-zloc
                          (z/insert-right* (p/parse-string-all "\n\n"))
                          z/right
                          (z/insert-right* (p/parse-string-all content-str))
                          z/right))]
         {:zloc updated-zloc
          :similar-matches (:similar-matches find-result)})))))

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

;; Partial formatting helpers

(defn re-indent-to-column
  "Re-indents a formatted form string so that:
   - The first line is unchanged (it will be placed at the correct column by rewrite-clj)
   - Subsequent lines are indented to align with the target column position.

   Arguments:
   - form-str: The formatted form string (formatted in isolation starting at column 1)
   - target-col: The 1-based column where the form starts in the file

   Returns:
   - The re-indented form string"
  [form-str target-col]
  (if (<= target-col 1)
    form-str
    (let [lines (str/split form-str #"\n" -1)
          indent-str (apply str (repeat (dec target-col) " "))
          re-indented (cons (first lines)
                            (map (fn [line]
                                   (if (str/blank? line)
                                     line
                                     (str indent-str line)))
                                 (rest lines)))]
      (str/join "\n" re-indented))))

(defn format-form-in-isolation
  "Formats a form string in isolation using cljfmt, then re-indents it
   to match the target column position. This avoids reformatting the
   entire file when only one form is being replaced.

   Arguments:
   - form-str: The form source code to format
   - target-col: The 1-based column where the form starts in the file
   - formatting-options: cljfmt formatting options

   Returns:
   - The formatted and re-indented form string, or the original on failure"
  [form-str target-col formatting-options]
  (try
    (let [formatted (fmt/reformat-string form-str formatting-options)]
      (re-indent-to-column formatted target-col))
    (catch Exception e
      (log/debug "Partial formatting failed, using original form:" (.getMessage e))
      form-str)))

;; File operations

(defn load-file-content
  "Loads content from a file.
   
   Arguments:
   - file-path: Path to the file
   
   Returns:
   - The file content as a string, or an error map if the file could not be read"
  [file-path]
  (try
    {:content (file-utils/slurp-utf8 file-path)
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
    (file-utils/spit-utf8 file-path content)
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

;; REPLACE MULTIPLE SEXPS

(def ^:dynamic *match-clean* false)

(defn semantic-nodes?
  "Returns true if node contributes to program semantics"
  [node]
  (if *match-clean*
    (not (n/whitespace-or-comment? node))
    (not (n/whitespace? node))))

(defn normalize-whitespace-node
  "Normalize whitespace within a node while preserving structure"
  [node]
  (if (= :forms (n/tag node))
    (n/forms-node (map normalize-whitespace-node (n/children node)))
    (if (n/inner? node)
      (let [children (n/children node)
            filtered (->> children
                          (remove n/whitespace?)
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

(defn node->match-expr [node]
  (when-let [n (if *match-clean*
                 (normalize-and-clean-node node)
                 (normalize-whitespace-node node))]
    (n/string n)))

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
  [zloc]
  (let [nodes (if (= :forms (z/tag zloc))
                 ;; If at forms node, get children
                (n/children (z/node zloc))
                 ;; Otherwise iterate through siblings
                (->> (iterate z/right* zloc)
                     (take-while some?)
                     (map z/node)))]
    (->> nodes
         (filter (bound-fn* semantic-nodes?))
         (keep (bound-fn* node->match-expr)))))

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

(defn zright-n [zloc n]
  (iterate-to-n z/right zloc n))

;; the given zloc should be add the start of the value to be replaced
;;

;; TRUNCATION
;; the key to replacing a multi-sexp expression is to truncate it down to
;; its first expression deleting all the other matched nodes
;; then it's just a normal replace edit after that

(defn remove-match-expr [zloc match-exp]
  (loop [zloc' zloc]
    (when (and zloc'
               (not (z/end? zloc')))
      (let [node (z/node zloc')]
        ;; if this is a semantic node and not a match then this
        ;; shoudn't occur as this function should only be called when
        ;; the next expression should be match-exp
        (when (and (semantic-nodes? node)
                   (not= match-exp (node->match-expr node)))
          (throw (ex-info "Bad match state" {:node (n/string node)
                                             :match-exp match-exp})))
        (if (and
             (semantic-nodes? node)
             (= match-exp (node->match-expr node)))
          (z/remove* zloc')
          (recur (-> zloc' z/remove* z/next*)))))))

(defn remove-match-exprs [zloc match-exprs]
  (let [end (last match-exprs)]
    (reduce
     (fn [zloc' match-expr]
       (let [zl (remove-match-expr zloc' match-expr)]
         (cond
           (= match-expr end) zl
           (not (z/end? zl)) (z/next* zl))))
     zloc
     match-exprs)))

(defn truncate-matched-expression [zloc [start & match-exprs]]
  (when-let [after-truncated (remove-match-exprs
                              (z/right* zloc)
                              match-exprs)]
    ;; find the start node
    (z/find after-truncated
            z/prev*
            (fn [zloc]
              (let [node (z/node zloc)]
                (and
                 (semantic-nodes? node)
                 (= start (node->match-expr node))))))))

(defn replace-multi-helper [zloc match-exprs content-str]
  (if (= 1 (count match-exprs))
    (replace-top-level-form zloc content-str)
    (let [truncated-zloc (truncate-matched-expression zloc match-exprs)]
      (replace-top-level-form truncated-zloc content-str))))

(defn replace-multi [zloc match-sexprs content-str]
  (if (or (nil? content-str) (zero? (count content-str)))
    (let [after-loc (remove-match-exprs zloc match-sexprs)]
      {:edit-span-loc after-loc
       :after-loc after-loc})
    (let [edit-span-loc (replace-multi-helper zloc match-sexprs content-str)]
      {:edit-span-loc edit-span-loc
       :after-loc (or (z/right edit-span-loc)
                      (z/next edit-span-loc))})))

(defn insert-before-multi [zloc _match-sexprs replacement-node]
  (let [edit-loc (-> zloc
                     walk-back-to-non-comment
                     z/next*
                     (z/insert-left replacement-node)
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

(defn find-and-edit-one-multi-sexp
  ([zloc operation match-form new-form]
   (find-and-edit-one-multi-sexp zloc operation match-form new-form nil))
  ([zloc operation match-form new-form reindent-fn]
   {:pre [(#{:insert-before :insert-after :replace} operation) zloc (string? match-form) (string? new-form)]}
   ;; no-op
   (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
     (let [match-sexprs (str-forms->sexps match-form)]
       (when-let [found-loc (find-multi-sexp zloc match-sexprs)]
         (let [adjusted-form (if reindent-fn
                               (if-let [col (some-> (z/position found-loc) second)]
                                 (reindent-fn new-form col)
                                 new-form)
                               new-form)
               new-node (when-not (str/blank? adjusted-form)
                          (p/parse-string-all adjusted-form))]
           (condp = operation
             :insert-before (insert-before-multi found-loc match-sexprs new-node)
             :insert-after (insert-after-multi found-loc match-sexprs new-node)
             (replace-multi found-loc match-sexprs adjusted-form))))))))

(defn find-and-edit-all-multi-sexp
  ([zloc operation match-form new-form]
   (find-and-edit-all-multi-sexp zloc operation match-form new-form nil))
  ([zloc operation match-form new-form reindent-fn]
   {:pre [(#{:insert-before :insert-after :replace} operation) zloc (string? match-form) (string? new-form)]}
   (when-not (and (str/blank? new-form) (#{:insert-before :insert-after} operation))
     (loop [loc zloc
            locations []]
       (if-let [{:keys [after-loc edit-span-loc]}
                (find-and-edit-one-multi-sexp loc operation match-form new-form reindent-fn)]
         (recur after-loc (conj locations edit-span-loc))
         (when-not (empty? locations)
           ;; this is a location after the last match
           ;; z/root-string on this will produce the final edited form
           {:zloc loc
            :locations locations}))))))

(defn find-and-edit-multi-sexp* [zloc match-form new-form {:keys [operation all? reindent-fn]}]
  (if all?
    (find-and-edit-all-multi-sexp zloc operation match-form new-form reindent-fn)
    (when-let [{:keys [after-loc edit-span-loc]} (find-and-edit-one-multi-sexp zloc operation match-form new-form reindent-fn)]
      ;; this is a location after the last match
      ;; z/root-string on this will produce the final edited form
      {:zloc after-loc
       :locations [edit-span-loc]})))

(defn find-and-edit-multi-sexp [zloc match-form new-form opts]
  (or (find-and-edit-multi-sexp* zloc match-form new-form opts)
      (binding [*match-clean* true]
        (find-and-edit-multi-sexp* zloc match-form new-form opts))))
