;; REPL Helper Functions for Clojure MCP

(ns clj-mcp.repl-tools
  "Namespace containing helper functions for REPL-driven development"
  (:require [clojure.pprint :as pprint]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; Namespace exploration
(defn list-ns
  "List all available namespaces, sorted alphabetically."
  []
  (let [namespaces (sort (map str (all-ns)))]
    (println "Available Namespaces:")
    (doseq [ns-name namespaces]
      (println (str "  " ns-name)))
    (println (str "\nTotal: " (count namespaces) " namespaces"))
    nil))

(defn list-vars
  "List all public vars in the given namespace with their arglists and docstrings.
   ns-name can be a symbol or string."
  [ns-nm]
  (let [ns-obj (if (symbol? ns-nm)
                 (find-ns ns-nm)
                 (find-ns (symbol ns-nm)))]
    (if ns-obj
      (let [vars (sort-by first (ns-publics ns-obj))]
        (println (str "Vars in " (ns-name ns-obj) ":"))
        (println (str "-------------------------------------------"))
        (doseq [[sym var] vars]
          (let [m (meta var)]
            (println (str (name sym)))
            (when-let [arglists (:arglists m)]
              (println (str "  " arglists)))
            (when-let [doc (:doc m)]
              (println (str "  " doc)))
            (println)))
        (println (str "Total: " (count vars) " vars")))
      (println (str "Error: Namespace not found: " ns-nm)))
    nil))

;; Symbol exploration
(defn doc-symbol
  "Show documentation for a symbol. Accepts symbol or string."
  [sym]
  (if-let [v (resolve (if (symbol? sym) sym (symbol sym)))]
    (let [m (meta v)]
      (println (str "-------------------------"))
      (println (str (:name m) " - " (or (:doc m) "No documentation")))
      (println (str "  Defined in: " (:ns m)))
      (when-let [arglists (:arglists m)]
        (println (str "  Arguments: " arglists)))
      (when-let [added (:added m)]
        (println (str "  Added in: " added)))
      (when-let [fn-spec (s/get-spec (symbol v))]
        (println "  Function Spec: " (with-out-str (pprint/pprint (s/describe fn-spec)))))
      (when-let [deprecated (:deprecated m)]
        (println (str "  DEPRECATED: " deprecated)))
      (println (str "-------------------------")))
    (println (str "Error: Symbol not found: " sym)))
  nil)

(defn doc-namespace
  "Show documentation for a namespace. Accepts symbol or string."
  [sym]
  (if-let [ns (find-ns (if (symbol? sym) sym (symbol sym)))]
    (let [m (meta ns)]
      (println (str "-------------------------"))
      (println (str (name sym) " - " (or (:doc m) "No documentation")))
      (println (str "-------------------------")))
    (println (str "Error: Namespace not found: " sym)))
  nil)

(defn source-symbol
  "Show source code for a var. Accepts symbol or string."
  [sym]
  (if-let [v (resolve (if (symbol? sym) sym (symbol sym)))]
    (if-let [source-fn (resolve 'clojure.repl/source-fn)]
      (println (source-fn (symbol v)))
      (println "Error: clojure.repl/source-fn not available"))
    (println (str "Error: Symbol not found: " sym)))
  nil)

(defn describe-spec
  "Show detailed information about a keyword/symbol/var `spec`."
  [spec]
  (if-let [spec-form (s/get-spec spec)]
    (do
      (println (str "-------------------------"))
      (println (str "Spec: " spec))
      (pprint/pprint (s/describe spec-form))
      (println (str "-------------------------")))
    (println (str "Error: Spec not found: " spec)))
  nil)

(defn find-symbols
  "Find symbols matching the given pattern across all namespaces.
   Pattern can be a string (for substring matching) or a regex pattern.
   Matches against both namespace and symbol name in the format 'namespace/symbol'."
  [pattern]
  (let [match-fn (cond
                   (instance? java.util.regex.Pattern pattern)
                   #(re-find pattern %)
                   :else
                   #(str/includes? % (str pattern)))

        matches (sort (for [ns (all-ns)
                            :let [ns-name (str (ns-name ns))]
                            [sym-name] (ns-publics ns)
                            :let [qualified-name (str ns-name "/" (name sym-name))]
                            :when (match-fn qualified-name)]
                        qualified-name))]
    (println (str "Symbols matching '" pattern "':"))
    (doseq [sym matches]
      (println (str "  " sym)))
    (println (str "\nTotal: " (count matches) " matches"))
    nil))

(defn complete
  "Find symbol completions for the given prefix.
   Works with namespaced symbols, unqualified symbols, and namespace prefixes."
  [prefix]
  (let [all-ns-strs (map str (all-ns))
        exact-ns (first (filter #(= prefix %) all-ns-strs))
        has-ns (str/includes? prefix "/")
        matches
        (cond
          ;; Exact namespace match - return all vars in that namespace
          exact-ns
          (map #(str exact-ns "/" %)
            (map name (keys (ns-publics (find-ns (symbol exact-ns))))))

          ;; Namespace prefix - return matching namespaces
          (and (not has-ns) (some #(.startsWith ^String % prefix) all-ns-strs))
          (filter #(.startsWith ^String % prefix) all-ns-strs)

          ;; Namespaced symbol - return matching symbols
          has-ns
          (let [ns-part (subs prefix 0 (str/index-of prefix "/"))
                sym-part (subs prefix (inc (str/index-of prefix "/")))
                ns-obj (find-ns (symbol ns-part))]
            (if ns-obj
              (let [ns-symbols (keys (ns-publics ns-obj))
                    matching-symbols (filter #(.startsWith ^String (name %) sym-part) ns-symbols)]
                (map #(str ns-part "/" (name %)) matching-symbols))
              []))

          ;; Unqualified symbol - return matching symbols from all namespaces
          :else
          (sort (distinct (mapcat (fn [ns]
                                    (let [matching-symbols (filter #(.startsWith ^String (name %) prefix)
                                                             (keys (ns-publics ns)))]
                                      (map name matching-symbols)))
                            (all-ns)))))]
    (println (str "Completions for '" prefix "':"))
    (doseq [m (sort matches)]
      (println (str "  " m)))
    (println (str "\nTotal: " (count matches) " matches"))
    nil))

(defn help
  "Show help for REPL helper functions."
  []
  (println "REPL Helper Functions:")
  (println "  clj-mcp.repl-tools/list-ns           - List all available namespaces")
  (println "  clj-mcp.repl-tools/list-vars         - List all vars in namespace")
  (println "  clj-mcp.repl-tools/doc-symbol        - Show documentation for symbol")
  (println "  clj-mcp.repl-tools/doc-namespace     - Show documentation for a namespace")
  (println "  clj-mcp.repl-tools/source-symbol     - Show source code for symbol")
  (println "  clj-mcp.repl-tools/describe-spec     - Show detailed information about spec")
  (println "  clj-mcp.repl-tools/find-symbols      - Find symbols matching pattern")
  (println "  clj-mcp.repl-tools/complete          - Find completions for prefix")
  (println "  clj-mcp.repl-tools/help              - Show this help message")
  (println)
  (println "Usage Examples:")
  (println "  (clj-mcp.repl-tools/list-ns)                     ; List all namespaces")
  (println "  (clj-mcp.repl-tools/list-vars 'clojure.string)   ; List functions in clojure.string")
  (println "  (clj-mcp.repl-tools/doc-symbol 'map)             ; Show documentation for map")
  (println "  (clj-mcp.repl-tools/doc-namespace 'clojure.repl) ; Show documentation for a namespace")
  (println "  (clj-mcp.repl-tools/source-symbol 'map)          ; Show source code for map")
  (println "  (clj-mcp.repl-tools/describe-spec :my/spec)      ; Show detailed information about a clojure spec")
  (println "  (clj-mcp.repl-tools/find-symbols \"seq\")          ; Find symbols containing \"seq\" (namespace/symbol)")
  (println "  (clj-mcp.repl-tools/find-symbols #\".*math.*\")    ; Find symbols matching regex pattern")
  (println "  (clj-mcp.repl-tools/complete \"clojure.string/j\") ; Find completions for prefix")
  (println)
  (println "For convenience, you can require the namespace with an alias:")
  (println "  (require '[clj-mcp.repl-tools :as rt])")
  (println "  (rt/list-ns)")
  (println)
  (println "To import all functions into the current namespace:")
  (println "  (use 'clj-mcp.repl-tools)"))

;; Print loading message
(println "REPL tools loaded in namespace clj-mcp.repl-tools")
(println "Type (clj-mcp.repl-tools/help) for more information")
