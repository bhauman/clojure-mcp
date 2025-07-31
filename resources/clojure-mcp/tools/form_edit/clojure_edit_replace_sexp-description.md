Replaces Clojure expressions in a file.

This tool provides targeted replacement of Clojure expressions within forms. For complete top-level form operations, use `clojure_edit` instead.

KEY BENEFITS:
- Syntax-aware matching that understands Clojure code structure
- Ignores whitespace differences by default, focusing on actual code meaning
- Matches expressions regardless of formatting, indentation, or spacing
- Prevents errors from mismatched text or irrelevant formatting differences
- Can replace all occurrences with replace_all: true

CONSTRAINTS:
- match_form must contain one or more complete Clojure expressions
- new_form must contain zero or more complete Clojure expressions
- Both match_form and new_form must be valid Clojure code that can be parsed

A complete Clojure expression is any form that Clojure can read as a complete unit:
- Symbols: foo, my-var, clojure.core/map
- Numbers: 42, 3.14
- Strings: "hello"
- Keywords: :keyword, ::namespaced
- Collections: [1 2 3], {:a 1}, #{:a :b}
- Function calls: (println "hello")
- Special forms: (if true 1 2)

WARNING: The following are NOT valid Clojure expressions and will cause errors:
- Incomplete forms: (defn foo, (try, (let [x 1]
- Partial function definitions: (defn foo [x] 
- Just the opening of a form: (if condition
- Mixed data without collection: :a 1 :b 2
- Unmatched parentheses: (+ 1 2))

COMMON APPLICATIONS:
- Renaming symbols throughout the file: 
    match_form:  old-name 
    new_form:    new-name
    replace_all: true

- Replacing multiple expressions with a single form:
    match_form:  (validate x) (transform x) (save x)
    new_form:    (-> x validate transform save)

- Wrapping code in try-catch:
    match_form:  (risky-op-1) (risky-op-2)
    new_form:    (try
                   (risky-op-1)
                   (risky-op-2)
                   (catch Exception e
                     (log/error e "Operations failed")))

- Removing debug statements:
    match_form:  (println "Debug 1") (println "Debug 2")
    new_form:    

- Converting imperative style to functional:
    match_form:  (def result (calculate x)) (println result) result
    new_form:    (doto (calculate x) println)

- Transforming let bindings:
    match_form:  [x (get-value) y (process x)]
    new_form:    [x (get-value) 
                  _ (log/debug "got value" x)
                  y (process x)]

Other Examples:
- Replace a calculation:
    match_form:  (+ x 2)
    new_form:    (* x 2)

- Clean up code by removing intermediate steps:
    match_form:  (let [temp (process x)] (use temp)) 
    new_form:    (use (process x))

- Change function calls:
    match_form:  (println "Processing" x)
    new_form:    (log/info "Processing item" x)

Returns a diff showing the changes made to the file.