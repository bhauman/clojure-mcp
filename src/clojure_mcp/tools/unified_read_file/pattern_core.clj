(ns clojure-mcp.tools.unified-read-file.pattern-core
  "Enhanced pattern-based Clojure file exploration with reader conditional support and collapsed view.
   
   Key features:
   - Pattern-based matching on form names and content
   - Collapsed view showing function signatures (e.g., (defn foo [x] ...))
   - Full support for reader conditionals (#? and #?@)
   - Platform-specific forms displayed with reader conditional syntax
   - String-based helper functions for easy REPL testing
   
   Example output for platform-specific forms:
   #?(:clj
      (defn server-fn [] ...))
   
   This makes it clear which forms are platform-specific without cluttering the form names."
  (:require
   [rewrite-clj.zip :as z]
   [rewrite-clj.node :as n]
   [clojure.string :as str]))

(defn seq-node? [node]
  (instance? rewrite_clj.node.seq.SeqNode node))

(defn node-tag [node]
  (try
    (cond
      (nil? node) nil
      (map? node) (:tag node)
      :else (n/tag node))
    (catch Exception _ nil)))

(defn valid-node-to-include?
  "Checks if a node should be included in analysis, excluding whitespace etc."
  [node include-comments]
  (try
    (when node
      (let [tag (n/tag node)]
        (not (or (= tag :uneval)
                 (= tag :whitespace)
                 (= tag :newline)
                 (and (not include-comments) (= tag :comment))))))
    (catch Exception _
      false)))

(defn extract-forms-from-reader-conditional
  "Extract forms from inside reader conditionals like #?(:clj ...) and #?@(:clj [...])"
  [reader-node]
  (try
    (let [children (n/children reader-node)]
      (when (>= (count children) 2)
        (let [token (n/string (first children))
              splicing? (= "?@" token)
              content-node (second children)]
          (when (seq-node? content-node)
            (let [content-children (n/children content-node)
                  ;; Filter out whitespace and newline nodes
                  non-whitespace (filter #(valid-node-to-include? % false) content-children)
                  ;; Group into platform/form pairs
                  pairs (partition 2 non-whitespace)]
              (mapcat (fn [[platform form-node]]
                        (when (and platform form-node)
                          (if (and splicing? (= :vector (n/tag form-node)))
                            ;; For splicing, extract forms from the vector
                            (let [vector-children (n/children form-node)]
                              (map (fn [child]
                                     {:node child
                                      :platform (n/sexpr platform)})
                                   (filter #(valid-node-to-include? % false)
                                           vector-children)))
                            ;; Regular reader conditional
                            [{:node form-node
                              :platform (n/sexpr platform)}])))
                      pairs))))))
    (catch Exception _ [])))

(defn valid-form-to-include?
  "Checks if a form should be included in analysis, excluding comments unless specified."
  [zloc include-comments]
  (try
    (when zloc
      (valid-node-to-include? (z/node zloc) include-comments))
    (catch Exception _
      false)))

(defn extract-form-name
  "Extracts name from a form, e.g., 'foo' from (defn foo [x] ...).
   For defmethod forms, includes the dispatch value, e.g., 'area :square'.
   For spec forms, handles keywords like ::email from (s/def ::email string?)."
  [sexpr]
  (try
    (when (and (seq? sexpr) (> (count sexpr) 1))
      (let [first-elem (first sexpr)
            second-elem (second sexpr)]
        (cond
          ;; Special handling for defmethod forms
          (and (symbol? first-elem)
               (= (name first-elem) "defmethod")
               (>= (count sexpr) 3))
          (let [method-sym second-elem
                method-name (if (and (symbol? method-sym) (namespace method-sym))
                              (str (namespace method-sym) "/" (name method-sym))
                              (name method-sym))
                dispatch-val (nth sexpr 2)
                dispatch-str (pr-str dispatch-val)]
            (str method-name " " dispatch-str))

          ;; Handle forms with keyword names (like s/def)
          (keyword? second-elem)
          (let [kw-name (name second-elem)
                kw-ns (namespace second-elem)]
            (cond
              ;; Auto-resolved keyword (::keyword)
              (= kw-ns "?_current-ns_?")
              (str "::" kw-name)

              ;; Fully qualified keyword
              kw-ns
              (str ":" kw-ns "/" kw-name)

              ;; Simple keyword
              :else
              (str ":" kw-name)))

          ;; Regular form handling with symbol names
          (symbol? second-elem)
          (name second-elem)

          :else nil)))
    (catch Exception _
      nil)))

(defn get-form-summary
  "Get a summarized representation of a Clojure form showing only up to the argument list.
   Adapted from form-edit/core.clj"
  [zloc]
  (try
    (let [sexpr (z/sexpr zloc)]
      (when (and (seq? sexpr) (symbol? (first sexpr)))
        (let [first-sym (first sexpr)
              form-type (name first-sym)
              form-name (extract-form-name sexpr)]

          ;; Check if it's a namespaced symbol (like s/def, s/fdef)
          (if (namespace first-sym)
            (cond
              ;; s/def, clojure.spec.alpha/def, etc.
              (str/ends-with? (str first-sym) "/def")
              (str "(" first-sym " " form-name " ...)")

              ;; s/fdef, clojure.spec.alpha/fdef, etc.
              (str/ends-with? (str first-sym) "/fdef")
              (str "(" first-sym " " form-name " ...)")

              :else
              (str "(" first-sym " " (or form-name "") " ...)"))

            ;; Non-namespaced symbols
            (case form-type
              "defn" (let [zloc-down (z/down zloc)
                           name-loc (and zloc-down (z/right zloc-down))
                           maybe-docstring (and name-loc (z/right name-loc))
                           args-loc (if (and maybe-docstring
                                             (contains? #{:token :multi-line} (z/tag maybe-docstring))
                                             (string? (z/sexpr maybe-docstring)))
                                      (z/right maybe-docstring)
                                      maybe-docstring)]
                       (if (and args-loc (= (z/tag args-loc) :vector))
                         (str "(defn " form-name " " (z/string args-loc) " ...)")
                         (str "(defn " form-name " [...] ...)")))

              "defmacro" (let [zloc-down (z/down zloc)
                               name-loc (and zloc-down (z/right zloc-down))
                               maybe-docstring (and name-loc (z/right name-loc))
                               args-loc (if (and maybe-docstring
                                                 (contains? #{:token :multi-line} (z/tag maybe-docstring))
                                                 (string? (z/sexpr maybe-docstring)))
                                          (z/right maybe-docstring)
                                          maybe-docstring)]
                           (if (and args-loc (= (z/tag args-loc) :vector))
                             (str "(defmacro " form-name " " (z/string args-loc) " ...)")
                             (str "(defmacro " form-name " [...] ...)")))

              "defmethod" (let [zloc-down (z/down zloc)
                                method-loc (and zloc-down (z/right zloc-down))
                                method-sym (and method-loc (z/sexpr method-loc))
                                method-name (if (symbol? method-sym)
                                              (if (namespace method-sym)
                                                (str (namespace method-sym) "/" (name method-sym))
                                                (name method-sym))
                                              "unknown")
                                dispatch-loc (and method-loc (z/right method-loc))
                                dispatch-val (and dispatch-loc (z/sexpr dispatch-loc))
                                dispatch-str (and dispatch-val (pr-str dispatch-val))
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

              (str "(" form-type " " (or form-name "") " ...)"))))))
    (catch Exception _
      ;; Provide a fallback in case of errors
      (try
        (let [raw-str (z/string zloc)]
          (if (< (count raw-str) 60)
            raw-str
            (str (subs raw-str 0 57) "...")))
        (catch Exception _
          nil)))))

(defn process-form-node
  "Process a form node and extract metadata, now including zloc"
  [node platform zloc]
  (try
    (let [sexpr (n/sexpr node)
          form-name (extract-form-name sexpr)
          form-type (when (and (seq? sexpr) (symbol? (first sexpr)))
                      (name (first sexpr)))
          form-content (n/string node)
          ;; If zloc is a reader-macro (happens with reader conditionals), 
          ;; create a proper zloc from the node inside
          actual-zloc (if (= :reader-macro (try (z/tag zloc) (catch Exception _ nil)))
                        (z/of-node node)
                        zloc)]
      (when form-name
        {:name form-name ;; No longer append platform to name
         :type form-type
         :content form-content
         :platform platform
         :zloc actual-zloc}))
    (catch Exception _ nil)))

(defn collect-top-level-forms-with-zloc*
  "Collects metadata about all top-level forms from a source string, including forms inside reader conditionals.
   Enhanced to also preserve zipper locations for collapsed view generation."
  [source-str include-comments]
  (try
    (let [zloc (z/of-string source-str)]
      (loop [loc zloc
             forms []]
        (if (nil? loc)
          forms
          (cond
            ;; Handle reader conditionals
            (= :reader-macro (z/tag loc))
            (let [reader-forms (extract-forms-from-reader-conditional (z/node loc))
                  ;; For reader conditionals, we use the parent zloc since individual forms
                  ;; inside don't have their own independent zlocs
                  processed-forms (->> reader-forms
                                       (map (fn [{:keys [node platform]}]
                                              (process-form-node node platform loc)))
                                       (filter some?))]
              (recur (try (z/right loc) (catch Exception _ nil))
                     (into forms processed-forms)))

            ;; Handle regular forms
            (valid-form-to-include? loc include-comments)
            (let [form-data (process-form-node (z/node loc) nil loc)]
              (recur (try (z/right loc) (catch Exception _ nil))
                     (if form-data
                       (conj forms form-data)
                       forms)))

            ;; Skip other nodes
            :else
            (recur (try (z/right loc) (catch Exception _ nil)) forms)))))
    (catch Exception e
      (throw (ex-info (str "Error collecting forms: " (.getMessage e))
                      {:source (subs source-str 0 (min 100 (count source-str)))})))))

(defn collect-top-level-forms-with-zloc
  "Collects metadata about all top-level forms in a Clojure file, including forms inside reader conditionals.
   Enhanced to also preserve zipper locations for collapsed view generation."
  [file-path include-comments]
  (try
    (let [file-content (slurp file-path)]
      (collect-top-level-forms-with-zloc* file-content include-comments))
    (catch java.io.FileNotFoundException _
      (throw (ex-info (str "Error: File not found: " file-path) {:file-path file-path})))
    (catch Exception e
      (throw (ex-info (str "Error reading file: " (.getMessage e))
                      {:file-path file-path} e)))))

(defn matches-pattern?
  "Check if a form matches the given name or content pattern"
  [form name-regex content-regex]
  (or (and name-regex (:name form) (re-find name-regex (:name form)))
      (and content-regex (:content form) (re-find content-regex (:content form)))))

(defn generate-collapsed-view*
  "Generates a collapsed view of Clojure code from a source string with pattern-based expansion.
   Forms matching the patterns are shown in full, others are collapsed.
   
   Arguments:
   - source-str: The Clojure source code as a string
   - name-pattern: Regex pattern to match form names (optional)
   - content-pattern: Regex pattern to match form content (optional)
   
   Returns:
   - A map with :view (the collapsed string) and :pattern-info"
  [source-str name-pattern content-pattern]
  (try
    (let [forms (collect-top-level-forms-with-zloc* source-str false)
          name-regex (when (and name-pattern (not= name-pattern ""))
                       (try (re-pattern name-pattern)
                            (catch Exception e
                              (throw (ex-info (str "Invalid name pattern regex: " (.getMessage e))
                                              {:pattern name-pattern})))))
          content-regex (when (and content-pattern (not= content-pattern ""))
                          (try (re-pattern content-pattern)
                               (catch Exception e
                                 (throw (ex-info (str "Invalid content pattern regex: " (.getMessage e))
                                                 {:pattern content-pattern})))))
          ;; Determine which forms should be expanded
          expanded-count (atom 0)
          view-parts (map (fn [form]
                            (let [should-expand (matches-pattern? form name-regex content-regex)
                                  zloc (:zloc form)
                                  platform (:platform form)]
                              (when should-expand
                                (swap! expanded-count inc))
                              (let [content (if should-expand
                                              ;; Show full content for matching forms
                                              (:content form)
                                              ;; Show collapsed summary for non-matching forms
                                              (or (get-form-summary zloc)
                                                  (:content form)))]
                                ;; Wrap platform-specific forms in reader conditional syntax
                                (if platform
                                  (str "#?(" platform "\n   "
                                       (str/replace content "\n" "\n   ")
                                       ")")
                                  content))))
                          forms)
          view-string (str/join "\n\n" (filter some? view-parts))]

      {:view view-string
       :pattern-info {:name-pattern name-pattern
                      :content-pattern content-pattern
                      :total-forms (count forms)
                      :expanded-forms @expanded-count
                      :collapsed-forms (- (count forms) @expanded-count)}})
    (catch Exception e
      (throw (ex-info (str "Error generating collapsed view: " (.getMessage e)) {} e)))))

(defn generate-collapsed-view
  "Generates a collapsed view of Clojure code with pattern-based expansion.
   Forms matching the patterns are shown in full, others are collapsed.
   
   Arguments:
   - file-path: Path to the Clojure file
   - name-pattern: Regex pattern to match form names (optional)
   - content-pattern: Regex pattern to match form content (optional)
   
   Returns:
   - A map with :view (the collapsed string) and :pattern-info"
  [file-path name-pattern content-pattern]
  (try
    (let [file-content (slurp file-path)]
      (generate-collapsed-view* file-content name-pattern content-pattern))
    (catch java.io.FileNotFoundException _
      (throw (ex-info (str "Error: File not found: " file-path) {:file-path file-path})))
    (catch Exception e
      (throw (ex-info (str "Error reading file: " (.getMessage e))
                      {:file-path file-path} e)))))

;; For backward compatibility, keep the original function but have it use the new implementation
(defn filter-forms-by-pattern
  "Filters forms based on name and/or content regex patterns.
   For defmethod forms, matches against the combined 'method-name dispatch-value' string."
  [forms name-pattern content-pattern]
  (let [name-regex (when (and name-pattern (not= name-pattern ""))
                     (try (re-pattern name-pattern)
                          (catch Exception e
                            (throw (ex-info (str "Invalid name pattern regex: " (.getMessage e))
                                            {:pattern name-pattern})))))
        content-regex (when (and content-pattern (not= content-pattern ""))
                        (try (re-pattern content-pattern)
                             (catch Exception e
                               (throw (ex-info (str "Invalid content pattern regex: " (.getMessage e))
                                               {:pattern content-pattern})))))
        matches (->> forms
                     (filter (fn [form]
                               (matches-pattern? form name-regex content-regex)))
                     (map (fn [{:keys [name platform]}]
                            {:name (if platform
                                     (str name " [" platform "]")
                                     name)
                             :platform platform})))]
    {:matches matches
     :pattern-info {:name-pattern name-pattern
                    :content-pattern content-pattern
                    :match-count (count matches)}}))

(defn collect-top-level-forms*
  "Collects metadata about all top-level forms from a source string, including forms inside reader conditionals."
  [source-str include-comments]
  (map #(dissoc % :zloc) (collect-top-level-forms-with-zloc* source-str include-comments)))

(defn collect-top-level-forms
  "Original function for backward compatibility - delegates to enhanced version"
  [file-path include-comments]
  (try
    (let [file-content (slurp file-path)]
      (collect-top-level-forms* file-content include-comments))
    (catch java.io.FileNotFoundException _
      (throw (ex-info (str "Error: File not found: " file-path) {:file-path file-path})))
    (catch Exception e
      (throw (ex-info (str "Error reading file: " (.getMessage e))
                      {:file-path file-path} e)))))

(defn generate-pattern-based-file-view*
  "Generates pattern-based file view from a source string.
   Returns matches and pattern info."
  [source-str name-pattern content-pattern]
  (let [forms (collect-top-level-forms* source-str false)
        result (filter-forms-by-pattern forms name-pattern content-pattern)
        matching-names (:matches result)]
    {:matches matching-names
     :pattern-info (:pattern-info result)}))

(defn generate-pattern-based-file-view
  "Generates collapsed view with pattern-based expansion of Clojure files.
   Kept for backward compatibility."
  [file-path name-pattern content-pattern]
  (try
    (let [file-content (slurp file-path)]
      (generate-pattern-based-file-view* file-content name-pattern content-pattern))
    (catch java.io.FileNotFoundException _
      (throw (ex-info (str "Error: File not found: " file-path) {:file-path file-path})))
    (catch Exception e
      (throw (ex-info (str "Error reading file: " (.getMessage e))
                      {:file-path file-path} e)))))

(comment
  ;; Test examples for collapsed view functionality

  ;; Define test source code
  (def test-source
    "(ns test.example
  (:require [clojure.string :as str]))

(defn helper
  \"Helper function\"
  [x]
  (* x 2))

(defn validate-input
  \"Validates input data\"
  [data]
  (and (map? data)
       (contains? data :id)
       (string? (:id data))))

(defn validate-user
  [user]
  (validate-input user))

(defmethod process :type-a
  [m]
  (assoc m :processed true))

(defmethod process :type-b
  [m]
  (update m :count inc))

(defmacro with-validation
  [binding & body]
  `(if (validate-input ~binding)
     (do ~@body)
     (throw (ex-info \"Invalid input\" {}))))")

  ;; Test 1: No patterns - everything collapsed
  (let [result (generate-collapsed-view* test-source nil nil)]
    (println "=== All Collapsed ===")
    (println (:view result))
    (println "\nInfo:" (:pattern-info result)))

  ;; Test 2: Name pattern - expand functions with "validate" in name
  (let [result (generate-collapsed-view* test-source "validate-user" nil)]
    (println "\n=== Name Pattern 'validate' ===")
    (println (:view result)))

  ;; Test 3: Content pattern - expand forms containing "validate-input"
  (let [result (generate-collapsed-view* test-source nil "validate-input")]
    (println "\n=== Content Pattern 'validate-input' ===")
    (println (:view result)))

  ;; Test 4: Specific defmethod by dispatch value
  (let [result (generate-collapsed-view* test-source "process :type-a|validate-input" nil)]
    (println "\n=== Specific defmethod 'process :type-a' ===")
    (println (:view result)))

  ;; Test 5: Combined patterns
  (let [result (generate-collapsed-view* test-source "validate" "map\\?")]
    (println "\n=== Combined: name 'validate' AND content 'map?' ===")
    (println (:view result)))

  ;; Reader conditionals test
  (def test-cljc
    "(ns test.cross-platform
  (:require [clojure.string :as str]))

(defn shared-helper
  \"Works on all platforms\"
  [x]
  (str/upper-case x))

(spec/def ::number number?)

#?(:clj
   (defn server-validate
     \"Server-side validation\"
     [data]
     (and (map? data)
          (.contains (str data) \"valid\"))))

#?(:cljs
   (defn client-validate
     \"Client-side validation\"
     [data]
     (and (map? data)
          (js/console.log \"Validating:\" data)
          true)))

#?@(:clj
    [(def server-config {:port 8080})
     (defn start-server [] 
       (println \"Starting on\" (:port server-config)))]
    :cljs
    [(def client-config {:endpoint \"http://localhost:8080\"})
     (defn connect-to-server []
       (println \"Connecting to\" (:endpoint client-config)))])

(defn another-shared-fn
  [x y]
  (+ x y))")

  ;; Test 6: Reader conditionals - no pattern
  (let [result (generate-collapsed-view* test-cljc nil nil)]
    (println "\n=== Reader Conditionals - All Collapsed ===")
    (println (:view result)))

  ;; Test 7: Reader conditionals - expand "validate" functions
  (let [result (generate-collapsed-view* test-cljc "validate" nil)]
    (println "\n=== Reader Conditionals - Pattern 'validate' ===")
    (println (:view result))
    (println "\nNotice: Platform labels like [:clj] are preserved"))

  ;; Test 8: Pattern-based file view (just matches, no collapsed view)
  (let [result (generate-pattern-based-file-view* test-source "validate" nil)]
    (println "\n=== Pattern Matches Only ===")
    (println "Matches:" (pr-str (:matches result))))

  ;; Test 9: Edge cases
  (def edge-case-source
    "(ns edge.cases)
     
;; This is a comment
(def ^:private secret \"Don't show\")

(defn ^{:doc \"Meta test\"} with-meta [x] x)

(defn- private-fn [] :private)

(defprotocol MyProtocol
  (my-method [this]))

(defrecord MyRecord [a b]
  MyProtocol
  (my-method [this] (:a this)))")

  (let [result (generate-collapsed-view* edge-case-source nil nil)]
    (println "\n=== Edge Cases ===")
    (println (:view result)))

  ;; Test 10: Namespace matching
  (def ns-qualified-source
    "(ns test.qualified)

(defmethod tool-system/validate :my-tool
  [_ inputs]
  {:valid? true})

(defmethod tool-system/execute :my-tool
  [_ inputs]
  (println \"Executing...\"))")

  (let [result (generate-collapsed-view* ns-qualified-source "tool-system/validate" nil)]
    (println "\n=== Namespace-qualified matching ===")
    (println (:view result)))

  ;; Utility to test form collection
  (let [forms (collect-top-level-forms* test-cljc false)]
    (println "\n=== Forms with platform info ===")
    (doseq [{:keys [name type platform]} forms]
      (println (format "%-30s %-10s %s"
                       name
                       (str "[" type "]")
                       (if platform (str "(" platform ")") ""))))))

