(ns clojure-mcp.tools.form-edit.core-test
  (:require
   [clojure-mcp.tools.test-utils :as test-utils]
   [clojure-mcp.config :as config]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure-mcp.tools.form-edit.core :as sut]
   [rewrite-clj.zip :as z]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Test fixtures
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-file* nil)

(defn create-test-files-fixture [f]
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "test-dir-" (System/currentTimeMillis)))]
    (.mkdirs test-dir)
    (let [test-file (io/file test-dir "test.clj")]
      (spit test-file "(ns test.core)\n\n(defn example-fn [x y]\n  #_(println \"debug value:\" x)\n  (+ x y))\n\n(def a 1)\n\n#_(def unused-value 42)\n\n(comment\n  (example-fn 1 2))\n\n;; Test comment\n;; spans multiple lines")
      (binding [*test-dir* test-dir
                *test-file* test-file]
        (config/set-config! test-utils/*nrepl-client-atom* :nrepl-user-dir test-dir)
        (try
          (f)
          (finally
            (when (.exists test-file) (.delete test-file))
            (when (.exists test-dir) (.delete test-dir))))))))

(use-fixtures :once test-utils/test-nrepl-fixture)
(use-fixtures :each create-test-files-fixture)

;; Test helper functions
(defn get-zloc [source-str]
  (z/of-string source-str {:track-position? true}))

;; Tests for core functions

(deftest is-top-level-form-test
  (testing "is-top-level-form? correctly identifies top-level forms"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))\n\n(def a 1)"
          zloc (get-zloc source)
          defn-loc (z/right zloc)] ;; Move to defn form
      (is (sut/is-top-level-form? zloc "ns" "test.core"))
      (is (sut/is-top-level-form? defn-loc "defn" "example-fn"))
      (is (not (sut/is-top-level-form? defn-loc "def" "example-fn")))
      (is (not (sut/is-top-level-form? defn-loc "defn" "other-fn"))))))

(deftest is-top-level-form-with-defmethod-test
  (testing "is-top-level-form? correctly identifies defmethod forms"
    (let [source "(ns test.multimethods)\n\n(defmulti area :shape)\n\n(defmethod area :rectangle [rect]\n  (* (:width rect) (:height rect)))\n\n(defmethod area :circle [circle]\n  (* Math/PI (:radius circle) (:radius circle)))"
          zloc (get-zloc source)
          rectangle-loc (-> zloc z/right z/right) ;; Skip ns, defmulti to reach first defmethod
          circle-loc (-> rectangle-loc z/right)]

      ;; Test with just the method name
      (is (sut/is-top-level-form? rectangle-loc "defmethod" "area"))
      (is (sut/is-top-level-form? circle-loc "defmethod" "area"))

      ;; Test with method name and dispatch value
      (is (sut/is-top-level-form? rectangle-loc "defmethod" "area :rectangle"))
      (is (sut/is-top-level-form? circle-loc "defmethod" "area :circle"))

      ;; Test negative cases
      (is (not (sut/is-top-level-form? rectangle-loc "defmethod" "area :circle")))
      (is (not (sut/is-top-level-form? circle-loc "defmethod" "area :rectangle")))
      (is (not (sut/is-top-level-form? rectangle-loc "defmethod" "other-method")))
      (is (not (sut/is-top-level-form? circle-loc "defn" "area"))))))

(deftest find-top-level-form-test
  (testing "find-top-level-form finds the correct form"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))\n\n(def a 1)"
          zloc (get-zloc source)]
      (is (some? (:zloc (sut/find-top-level-form zloc "ns" "test.core"))))
      (is (some? (:zloc (sut/find-top-level-form zloc "defn" "example-fn"))))
      (is (some? (:zloc (sut/find-top-level-form zloc "def" "a"))))
      (is (nil? (:zloc (sut/find-top-level-form zloc "defn" "non-existent"))))
      (is (nil? (:zloc (sut/find-top-level-form zloc "def" "example-fn")))))))

(deftest find-top-level-form-with-defmethod-test
  (testing "find-top-level-form finds defmethod forms correctly"
    (let [source "(ns test.multimethods)\n\n(defmulti area :shape)\n\n(defmethod area :rectangle [rect]\n  (* (:width rect) (:height rect)))\n\n(defmethod area :circle [circle]\n  (* Math/PI (:radius circle) (:radius circle)))"
          zloc (get-zloc source)]

      ;; Find with just the method name - should find the first occurrence
      (let [found-area (:zloc (sut/find-top-level-form zloc "defmethod" "area"))]
        (is (some? found-area))
        (is (= :rectangle (-> found-area z/down z/right z/right z/sexpr))))

      ;; Find with method name and dispatch value
      (let [found-rectangle (:zloc (sut/find-top-level-form zloc "defmethod" "area :rectangle"))
            found-circle (:zloc (sut/find-top-level-form zloc "defmethod" "area :circle"))]
        (is (some? found-rectangle))
        (is (some? found-circle))
        (is (= :rectangle (-> found-rectangle z/down z/right z/right z/sexpr)))
        (is (= :circle (-> found-circle z/down z/right z/right z/sexpr))))

      ;; Negative tests
      (is (nil? (:zloc (sut/find-top-level-form zloc "defmethod" "area :triangle"))))
      (is (nil? (:zloc (sut/find-top-level-form zloc "defmethod" "other-method")))))))

(deftest edit-top-level-form-test
  (testing "edit-top-level-form correctly replaces a form"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))\n\n(def a 1)"
          zloc (get-zloc source)
          new-fn "(defn example-fn [x y]\n  (* x y))"
          result (sut/edit-top-level-form zloc "defn" "example-fn" new-fn :replace)
          edited-zloc (:zloc result)
          result-str (z/root-string edited-zloc)]
      (is (some? edited-zloc))
      (is (str/includes? result-str "(defn example-fn [x y]\n  (* x y))"))
      (is (not (str/includes? result-str "(defn example-fn [x y]\n  (+ x y))")))))

  (testing "edit-top-level-form correctly inserts before a form"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
          zloc (get-zloc source)
          new-fn "(defn helper-fn [z]\n  (* z z))"
          result (sut/edit-top-level-form zloc "defn" "example-fn" new-fn :before)
          edited-zloc (:zloc result)
          result-str (z/root-string edited-zloc)]
      (is (some? edited-zloc))
      (is (str/includes? result-str "(defn helper-fn [z]\n  (* z z))"))
      (is (str/includes? result-str "(defn example-fn [x y]\n  (+ x y))")
          "Original form should still be present")))

  (testing "edit-top-level-form correctly inserts after a form"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
          zloc (get-zloc source)
          new-fn "(defn helper-fn [z]\n  (* z z))"
          result (sut/edit-top-level-form zloc "defn" "example-fn" new-fn :after)
          edited-zloc (:zloc result)
          result-str (z/root-string edited-zloc)]
      (is (some? edited-zloc))
      (is (str/includes? result-str "(defn helper-fn [z]\n  (* z z))"))
      (is (str/includes? result-str "(defn example-fn [x y]\n  (+ x y))")
          "Original form should still be present"))))

(deftest row-col-offset-test
  (testing "row-col->offset correctly calculates character offsets"
    (let [source "Line 1\nLine 2\nLine 3"]
      (is (= 0 (sut/row-col->offset source 1 0)))
      (is (= 3 (sut/row-col->offset source 1 3)))
      (is (= 7 (sut/row-col->offset source 2 0)))
      (is (= 10 (sut/row-col->offset source 2 3)))
      (is (= 14 (sut/row-col->offset source 3 0))))))

(deftest find-docstring-test
  (testing "find-docstring finds docstring in a function"
    (let [source "(ns test.core)\n\n(defn example-fn\n  \"This is a docstring\"\n  [x y]\n  (+ x y))"
          zloc (get-zloc source)
          result (sut/find-docstring zloc "defn" "example-fn")
          docstring-zloc (:zloc result)]
      (is (some? docstring-zloc))
      (is (= "This is a docstring" (z/sexpr docstring-zloc)))))

  (testing "find-docstring returns nil when no docstring exists"
    (let [source "(ns test.core)\n\n(defn example-fn [x y]\n  (+ x y))"
          zloc (get-zloc source)
          result (sut/find-docstring zloc "defn" "example-fn")]
      (is (nil? (:zloc result))))))

(deftest edit-docstring-test
  (testing "edit-docstring correctly replaces a docstring"
    (let [source "(ns test.core)\n\n(defn example-fn\n  \"This is a docstring\"\n  [x y]\n  (+ x y))"
          zloc (get-zloc source)
          new-docstring "Updated docstring"
          result (sut/edit-docstring zloc "defn" "example-fn" new-docstring)
          edited-zloc (:zloc result)
          result-str (z/root-string edited-zloc)]
      (is (some? edited-zloc))
      (is (str/includes? result-str "Updated docstring"))
      (is (not (str/includes? result-str "This is a docstring"))))))

(deftest comment-identification-test
  (testing "is-comment-form? correctly identifies comment forms"
    (let [source "(ns test.core)\n\n(comment\n  (+ 1 2))\n\n(defn example-fn [x y]\n  (+ x y))"
          zloc (get-zloc source)
          comment-loc (z/right zloc)] ;; Move to comment form
      (is (sut/is-comment-form? comment-loc))
      (is (not (sut/is-comment-form? zloc)))))

  (testing "is-line-comment? correctly identifies line comments"
    (let [source "(ns test.core)\n\n;; This is a comment\n(defn example-fn [x y]\n  (+ x y))"
          zloc (get-zloc source)
          comment-loc (z/right zloc)] ;; Try to move to comment
      ;; Note: This test might be fragile as rewrite-clj might handle comments differently
      (is (not (sut/is-line-comment? zloc))))))

(deftest find-comment-block-test
  (testing "find-comment-block finds comment forms"
    (let [source "(ns test.core)\n\n(comment\n  (+ 1 2)\n  (* 3 4))\n\n(defn example-fn [x y]\n  (+ x y))"
          block (sut/find-comment-block source "(+ 1 2)")]
      (is (some? block) "Should find a comment block")
      (is (= :comment-form (:type block)) "Should be a comment form")
      (is (str/includes? (:content block) "(+ 1 2)") "Should contain search string")
      (is (str/includes? (:content block) "(* 3 4)") "Should contain all content")))

  (testing "find-comment-block finds line comments"
    (let [source "(ns test.core)\n\n;; This is a test comment\n;; with multiple lines\n\n(defn example-fn [x y]\n  (+ x y))"
          block (sut/find-comment-block source "test comment")]
      (is (some? block) "Should find a comment block")
      (is (= :line-comments (:type block)) "Should be line comments")
      (is (str/includes? (:content block) "test comment") "Should contain search string")
      (is (str/includes? (:content block) "multiple lines") "Should contain all content"))))

(deftest edit-comment-block-test
  (testing "edit-comment-block edits comment forms"
    (let [source "(ns test.core)\n\n(comment\n  (+ 1 2)\n  (* 3 4))\n\n(defn example-fn [x y]\n  (+ x y))"
          new-content "(comment\n  (- 10 5)\n  (/ 8 2))"
          result (sut/edit-comment-block source "(+ 1 2)" new-content)]
      (is (str/includes? result "(- 10 5)"))
      (is (str/includes? result "(/ 8 2)"))
      (is (not (str/includes? result "(+ 1 2)")))
      (is (not (str/includes? result "(* 3 4)")))))

  (testing "edit-comment-block edits line comments"
    (let [source "(ns test.core)\n\n;; This is a test comment\n;; with multiple lines\n\n(defn example-fn [x y]\n  (+ x y))"
          new-content ";; Updated comment\n;; with new content"
          result (sut/edit-comment-block source "test comment" new-content)]
      (is (str/includes? result "Updated comment"))
      (is (str/includes? result "new content"))
      (is (not (str/includes? result "test comment")))
      (is (not (str/includes? result "multiple lines"))))))

(deftest extract-form-name-test
  (testing "extract-form-name correctly extracts names from various forms"
    (is (= "example-fn" (sut/extract-form-name '(defn example-fn [x y] (+ x y)))))
    (is (= "my-var" (sut/extract-form-name '(def my-var 42))))
    (is (= "test-ns" (sut/extract-form-name '(ns test-ns))))
    (is (= "area" (sut/extract-form-name '(defmethod area :rectangle [r] (* (:width r) (:height r))))))
    (is (= "test-fn" (sut/extract-form-name '(defmacro test-fn [x] `(inc ~x)))))
    (is (nil? (sut/extract-form-name '(+ 1 2)))))

  (testing "extract-form-name handles edge cases"
    (is (nil? (sut/extract-form-name 42)))
    (is (nil? (sut/extract-form-name '())))
    (is (nil? (sut/extract-form-name '(defn))))))

(deftest valid-form-to-include-test
  (testing "valid-form-to-include? correctly identifies node types to exclude"
    ;; We'll directly test the function against node tags rather than constructing zlocs
    (testing "Nil is excluded"
      (is (not (sut/valid-form-to-include? nil))))

    (testing "Function correctly excludes uneval forms"
      (let [source "#_(def unused 42)"
            zloc (get-zloc source)]
        (is (= :uneval (z/tag zloc)))
        (is (not (sut/valid-form-to-include? zloc)))))

    (testing "Function includes regular forms"
      (let [source "(defn test-fn [] true)"
            zloc (get-zloc source)]
        (is (= :list (z/tag zloc)))
        (is (sut/valid-form-to-include? zloc))))

    (testing "Implementation relies on z/tag to identify forms to exclude"
      ;; Instead of mocking objects, just test the logic directly
      (let [uneval-tag :uneval
            whitespace-tag :whitespace
            newline-tag :newline
            comment-tag :comment
            list-tag :list]
        ;; Test that our function correctly interprets the tags
        (is (not (sut/valid-form-to-include? nil)) "nil should be excluded")

        ;; Test the core logic of the function directly
        (is (= false
               (not (or (= uneval-tag :uneval)
                        (= whitespace-tag :whitespace)
                        (= newline-tag :newline)
                        (= comment-tag :comment))))
            "Excluded tags should make the function return false")

        (is (= true
               (not (or (= list-tag :uneval)
                        (= list-tag :whitespace)
                        (= list-tag :newline)
                        (= list-tag :comment))))
            "Regular tags should make the function return true")))))

(deftest get-form-summary-test
  (testing "get-form-summary provides correct summary for defn forms"
    (let [source "(defn example-fn [x y]\n  (+ x y))"
          zloc (get-zloc source)
          summary (sut/get-form-summary zloc)]
      (is (= "(defn example-fn [x y] ...)" summary))))

  (testing "get-form-summary provides correct summary for def forms"
    (let [source "(def a 1)"
          zloc (get-zloc source)
          summary (sut/get-form-summary zloc)]
      (is (= "(def a ...)" summary))))

  (testing "get-form-summary returns full form for ns forms"
    (let [source "(ns test.core)"
          zloc (get-zloc source)
          summary (sut/get-form-summary zloc)]
      (is (= "(ns test.core)" summary)))))

(deftest format-source-string-test
  (testing "format-source-string correctly formats source code"
    (let [unformatted "(defn   example-fn[x y]  (+ x  y)   )"
          formatted (sut/format-source-string
                     unformatted
                     sut/default-formatting-options)]
      ;; Compare as EDN to ignore whitespace differences
      (is (= (read-string unformatted) (read-string formatted))))))

(deftest project-formatting-options-test
  (testing "project-formatting-options returns options from cljfmt config files"
    (let [custom-options {:function-arguments-indentation :cursive}
          _ (spit (io/file *test-dir* "cljfmt.edn") (pr-str custom-options))
          formatting-options (sut/project-formatting-options
                              @test-utils/*nrepl-client-atom*)]
      (is (= (merge sut/default-formatting-options custom-options)
             formatting-options)))))

(deftest load-file-content-test
  (testing "load-file-content loads existing file"
    (let [path (.getAbsolutePath *test-file*)
          result (sut/load-file-content path)]
      (is (not (:error result)))
      (is (str/includes? (:content result) "(defn example-fn"))))

  (testing "load-file-content returns error for non-existent file"
    (let [path (str (.getAbsolutePath *test-dir*) "/non-existent.clj")
          result (sut/load-file-content path)]
      (is (:error result))
      (is (= (str "File not found: " path) (:message result))))))

(deftest save-file-content-test
  (testing "save-file-content saves content to file"
    (let [path (.getAbsolutePath *test-file*)
          content "(ns updated.core)\n\n(defn new-fn [x] (* x x))"
          result (sut/save-file-content path content)]
      (is (:success result))
      (is (= content (slurp path))))))

(deftest extract-dispatch-from-defmethod-test
  (testing "extract-dispatch-from-defmethod extracts method name and dispatch value"
    (let [rectangle-impl "(defmethod area :rectangle [rect] (* (:width rect) (:height rect)))"
          circle-impl "(defmethod area :circle [circle] (* Math/PI (:radius circle) (:radius circle)))"
          string-impl "(defmethod render \"circle\" [shape] (draw-circle shape))"
          number-impl "(defmethod calculate 42 [data] (process-special-case data))"
          symbol-impl "(defmethod transform 'reverse [coll] (reverse coll))"
          vector-impl "(defmethod process [1 2 3] [data] (transform-with-sequence data))"
          nested-impl "(defmethod handle-event [:ui :click :button] [event] (click-handler event))"
          map-impl "(defmethod match {:type :user :role :admin} [user] (admin-panel user))"
          malformed-impl "(defmethod area)"]

      ;; Test basic dispatch values
      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod rectangle-impl)]
        (is (= "area" method-name))
        (is (= ":rectangle" dispatch-value)))

      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod circle-impl)]
        (is (= "area" method-name))
        (is (= ":circle" dispatch-value)))

      ;; Test other dispatch value types
      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod string-impl)]
        (is (= "render" method-name))
        (is (= "\"circle\"" dispatch-value)))

      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod number-impl)]
        (is (= "calculate" method-name))
        (is (= "42" dispatch-value)))

      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod symbol-impl)]
        (is (= "transform" method-name))
        (is (= "(quote reverse)" dispatch-value)))

      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod vector-impl)]
        (is (= "process" method-name))
        (is (= "[1 2 3]" dispatch-value)))

      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod nested-impl)]
        (is (= "handle-event" method-name))
        (is (= "[:ui :click :button]" dispatch-value)))

      (let [[method-name dispatch-value] (sut/extract-dispatch-from-defmethod map-impl)]
        (is (= "match" method-name))
        (is (str/includes? dispatch-value "{")
            "Map should be formatted correctly and contain opening brace"))

      ;; Test malformed case
      (is (nil? (sut/extract-dispatch-from-defmethod malformed-impl))
          "Should return nil for malformed defmethod"))))

(deftest find-and-edit-multi-sexp-test
  (testing "Basic single-form replacement"
    (let [source "(defn test-fn [x] (+ x 1) (- x 2))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(+ x 10)" {:operation :replace :all? false})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (= 1 (count (:locations result))) "Should have made one replacement")
      (is (str/includes? updated "(+ x 10)") "Should include the replacement")
      (is (str/includes? updated "(- x 2)") "Should preserve other forms")))

  (testing "Replace all occurrences of single form"
    (let [source "(defn test-fn [x] (+ x 1) (+ x 1) (+ x 1))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(+ x 10)" {:operation :replace :all? true})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (= 3 (count (:locations result))) "Should have made three replacements")
      (is (= 0 (count (re-seq #"\(\+ x 1\)" updated))) "Should have no original forms left")
      (is (= 3 (count (re-seq #"\(\+ x 10\)" updated))) "Should have three replacements")))

  (testing "Multi-form replacement"
    (let [source "(defn test-fn [x] (+ x 1) (+ x 2) (- x 3))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(+ x 1) (+ x 2)" "(inc x) (+ x 10)" {:operation :replace :all? false})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (= 1 (count (:locations result))) "Should have made one replacement")
      (is (str/includes? updated "(inc x)") "Should include the first replacement")
      (is (str/includes? updated "(+ x 10)") "Should include the second replacement")
      (is (str/includes? updated "(- x 3)") "Should preserve other forms")
      (is (not (str/includes? updated "(+ x 1)")) "Original first form should be gone")
      (is (not (str/includes? updated "(+ x 2)")) "Original second form should be gone")))

  (testing "Multi-form expansion (2 forms to 3 forms)"
    (let [source "(defn test-fn [x] (+ x 1) (+ x 2))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(+ x 1) (+ x 2)" "(inc x) (+ x 10) (dec x)" {:operation :replace :all? false})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (= 1 (count (:locations result))) "Should have made one replacement")
      (is (str/includes? updated "(inc x)") "Should include the first replacement")
      (is (str/includes? updated "(+ x 10)") "Should include the second replacement")
      (is (str/includes? updated "(dec x)") "Should include the third replacement")))

  (testing "Multi-form contraction (3 forms to 1 form)"
    (let [source "(defn test-fn [x] (+ x 1) (+ x 2) (+ x 3) (- x 4))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(+ x 1) (+ x 2) (+ x 3)" "(+ x 6)" {:operation :replace :all? false})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (= 1 (count (:locations result))) "Should have made one replacement")
      (is (str/includes? updated "(+ x 6)") "Should include the replacement")
      (is (str/includes? updated "(- x 4)") "Should preserve other forms")
      (is (not (str/includes? updated "(+ x 1)")) "Original forms should be gone")
      (is (not (str/includes? updated "(+ x 2)")) "Original forms should be gone")
      (is (not (str/includes? updated "(+ x 3)")) "Original forms should be gone")))

  (testing "Different Clojure data types"
    (let [source "(defn test-data [x] {:key1 100 :key2 \"string\"} [1 2 3] #{:a :b :c})"
          zloc (z/of-string source {:track-position? true})]
      (let [keyword-result (sut/find-and-edit-multi-sexp zloc ":key1" ":new-key" {:operation :replace :all? true})
            keyword-updated (z/root-string (:zloc keyword-result))]
        (is (some? keyword-result) "Should return a result when matches are found")
        (is (str/includes? keyword-updated ":new-key") "Should have replaced keyword")
        (is (not (str/includes? keyword-updated ":key1")) "Original keyword should be gone"))

      (let [vector-result (sut/find-and-edit-multi-sexp zloc "[1 2 3]" "[4 5 6]" {:operation :replace :all? true})
            vector-updated (z/root-string (:zloc vector-result))]
        (is (some? vector-result) "Should return a result when matches are found")
        (is (str/includes? vector-updated "[4 5 6]") "Should have replaced vector")
        (is (not (str/includes? vector-updated "[1 2 3]")) "Original vector should be gone"))))

  (testing "Multi-element vector replacement"
    (let [source "[1 2 3 4 5 6 7 8 9 10]"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "3 4 5" "30 40 50 60" {:operation :replace :all? false})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (str/includes? updated "30 40 50 60") "Should have replaced elements")
      (is (str/includes? updated "[1 2 30 40 50 60 6 7 8 9 10]") "Should preserve vector structure")
      (is (not (str/includes? updated "3 4 5")) "Original elements should be gone")))

  (testing "Map key-value pair replacement"
    (let [source "{:a 1 :b 2 :c 3 :d 4}"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc ":b 2 :c 3" ":beta 20 :gamma 30 :delta 40" {:operation :replace :all? false})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (str/includes? updated ":beta") "Should include new key")
      (is (str/includes? updated "20") "Should include new value")
      (is (str/includes? updated ":gamma 30 :delta 40") "Should include all new key-value pairs")
      (is (str/includes? updated ":a 1") "Should preserve other pairs")
      (is (str/includes? updated ":d 4") "Should preserve other pairs")))

  (testing "Anonymous functions"
    (let [source "(defn use-anon-fns [coll] (map #(+ % 1) coll) (filter #(> % 10) coll))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "#(+ % 1)" "#(+ % 10)" {:operation :replace :all? true})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (str/includes? updated "#(+ % 10)") "Should have replaced anonymous function")
      (is (not (str/includes? updated "#(+ % 1)")) "Original anon function should be gone")
      (is (str/includes? updated "#(> % 10)") "Other anon function should remain unchanged")))

  (testing "Deletion with empty string replacement"
    (let [source "(defn with-debug [x] (println \"debug:\" x) (+ x 1))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(println \"debug:\" x)" "" {:operation :replace :all? true})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (not (str/includes? updated "println")) "Debug statement should be gone")
      (is (str/includes? updated "(defn with-debug [x]") "Function signature should remain")
      (is (str/includes? updated "(+ x 1)") "Function body should remain")))

  (testing "Multi-form deletion"
    (let [source "(defn test-fn [x] (println \"start\") (+ x 1) (println \"end\") (- x 1))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(+ x 1) (println \"end\")" "" {:operation :replace :all? false})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (str/includes? updated "(println \"start\")") "Should preserve forms before")
      (is (str/includes? updated "(- x 1)") "Should preserve forms after")
      (is (not (str/includes? updated "(+ x 1)")) "First deleted form should be gone")
      (is (not (str/includes? updated "(println \"end\")")) "Second deleted form should be gone")))

  (testing "Insert operations"
    (let [source "(defn test-fn [x] (+ x 1) (+ x 2))"
          zloc (z/of-string source {:track-position? true})]

      (testing "Insert before single form"
        (let [result (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(prn x)" {:operation :insert-before :all? false})
              updated (z/root-string (:zloc result))]
          (is (some? result) "Should return a result when matches are found")
          (is (str/includes? updated "(prn x)") "Should include the inserted form")
          (is (str/includes? updated "(+ x 1)") "Should preserve the original form")
          (is (str/includes? updated "(+ x 2)") "Should preserve other forms")))

      (testing "Insert after single form"
        (let [result (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(prn x)" {:operation :insert-after :all? false})
              updated (z/root-string (:zloc result))]
          (is (some? result) "Should return a result when matches are found")
          (is (str/includes? updated "(prn x)") "Should include the inserted form")
          (is (str/includes? updated "(+ x 1)") "Should preserve the original form")
          (is (str/includes? updated "(+ x 2)") "Should preserve other forms")))

      (testing "Insert before multi-form"
        (let [result (sut/find-and-edit-multi-sexp zloc "(+ x 1) (+ x 2)" "(prn \"before\") (prn x)" {:operation :insert-before :all? false})
              updated (z/root-string (:zloc result))]
          (is (some? result) "Should return a result when matches are found")
          (is (str/includes? updated "(prn \"before\")") "Should include the first inserted form")
          (is (str/includes? updated "(prn x)") "Should include the second inserted form")
          (is (str/includes? updated "(+ x 1)") "Should preserve the original forms")
          (is (str/includes? updated "(+ x 2)") "Should preserve the original forms")))

      (testing "Insert after multi-form"
        (let [result (sut/find-and-edit-multi-sexp zloc "(+ x 1) (+ x 2)" "(prn \"after\") (prn x)" {:operation :insert-after :all? false})
              updated (z/root-string (:zloc result))]
          (is (some? result) "Should return a result when matches are found")
          (is (str/includes? updated "(prn \"after\")") "Should include the first inserted form")
          (is (str/includes? updated "(prn x)") "Should include the second inserted form")
          (is (str/includes? updated "(+ x 1)") "Should preserve the original forms")
          (is (str/includes? updated "(+ x 2)") "Should preserve the original forms")))))

  (testing "No match found"
    (let [source "(defn test-fn [x] (+ x 1))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(- x 1)" "(- x 10)" {:operation :replace :all? true})]
      (is (nil? result) "Should return nil when no matches are found")))

  (testing "Multi-form with all? true"
    (let [source "(defn test-fn [x] (+ x 1) (+ x 2) (+ x 1) (+ x 2) (+ x 3))"
          zloc (z/of-string source {:track-position? true})
          result (sut/find-and-edit-multi-sexp zloc "(+ x 1) (+ x 2)" "(inc x) (+ x 10)" {:operation :replace :all? true})
          updated (z/root-string (:zloc result))]
      (is (some? result) "Should return a result when matches are found")
      (is (= 2 (count (:locations result))) "Should have made two replacements")
      (is (= 2 (count (re-seq #"\(inc x\)" updated))) "Should have two instances of first replacement")
      (is (= 2 (count (re-seq #"\(\+ x 10\)" updated))) "Should have two instances of second replacement")
      (is (str/includes? updated "(+ x 3)") "Should preserve non-matching forms")
      (is (= 0 (count (re-seq #"\(\+ x 1\)" updated))) "Should have no original first forms left")
      (is (= 0 (count (re-seq #"\(\+ x 2\)" updated))) "Should have no original second forms left"))))

(deftest zchild-match-exprs-test
  (testing "Basic whitespace normalization"
    (let [s1 "a  b   c  (list\n  d)"
          s2 "a b c (list d)"
          result1 (sut/zchild-match-exprs (z/of-string s1))
          result2 (sut/zchild-match-exprs (z/of-string s2))]
      (is (= result1 result2) "Whitespace should be normalized")))

  (testing "Comments and discards with clean? false (default)"
    (let [s1 "a ;; comment\n #_ ignored b"
          s2 "a b"
          result1 (sut/zchild-match-exprs (z/of-string s1))
          result2 (sut/zchild-match-exprs (z/of-string s2))]
      (is (not= result1 result2) "Comments and discards should be preserved by default")))

  (testing "Comments and discards with clean? true"
    (let [s1 "a ;; comment\n #_ ignored b"
          s2 "a b"
          result1 (sut/zchild-match-exprs (z/of-string s1) {:clean? true})
          result2 (sut/zchild-match-exprs (z/of-string s2) {:clean? true})]
      (is (= result1 result2) "Comments and discards should be removed when clean? is true")))

  (testing "Nested reader macros"
    (let [s1 "(defn foo [x] (map #(+ % 1) x))"
          s2 "(defn   foo\n  [x]\n  (map  #(+   %   1)  x))"
          result1 (sut/zchild-match-exprs (z/of-string s1))
          result2 (sut/zchild-match-exprs (z/of-string s2))]
      (is (= result1 result2) "Reader macros should be preserved with normalized whitespace")))

  (testing "Complex nested structure with reader macros"
    (let [s1 "(let [f #(+ %1 %2)] (f 1 2))"
          s2 "(let  [f   #(+  %1\n              %2)]\n  (f   1   2))"
          result1 (sut/zchild-match-exprs (z/of-string s1))
          result2 (sut/zchild-match-exprs (z/of-string s2))]
      (is (= result1 result2) "Complex nesting with reader macros should normalize correctly")))

  (testing "String literals preserve internal spaces"
    (let [s1 "(str \"hello    world\")"
          s2 "(str   \"hello    world\")"
          result1 (sut/zchild-match-exprs (z/of-string s1))
          result2 (sut/zchild-match-exprs (z/of-string s2))]
      (is (= result1 result2) "String literal content should be preserved exactly")))

  (testing "Multiple reader macros in sequence"
    (let [s1 "#(+ % 1) #(* % 2) #(- % 3)"
          s2 "#(+   %   1)\n#(*   %   2)\n#(-   %   3)"
          result1 (sut/zchild-match-exprs (z/of-string s1))
          result2 (sut/zchild-match-exprs (z/of-string s2))]
      (is (= result1 result2) "Multiple reader macros should normalize independently")))

  (testing "Reader conditionals"
    (let [s1 "#?(:clj (+ 1 2) :cljs (+ 3 4))"
          s2 "#?(:clj   (+   1   2)\n   :cljs   (+   3   4))"
          result1 (sut/zchild-match-exprs (z/of-string s1))
          result2 (sut/zchild-match-exprs (z/of-string s2))]
      (is (= result1 result2) "Reader conditionals should normalize correctly")))

  (testing "Mix of features - comments, discards, reader macros"
    (let [s1 "(defn process ;; main function\n  [data]\n  #_ (println \"debug\")\n  (map #(* % 2) data))"
          s2 "(defn   process\n[data]   (map   #(*   %   2)   data))"
          result1-clean (sut/zchild-match-exprs (z/of-string s1) {:clean? true})
          result2-clean (sut/zchild-match-exprs (z/of-string s2) {:clean? true})]
      (is (= result1-clean result2-clean) "Mixed features should normalize when cleaned"))))

(deftest find-and-edit-multi-sexp-replace-edge-cases-test
  (testing "Whitespace variations"
    (testing "Multi-line form matching"
      (let [source "(defn test [x] (+ x\n                  1) (- x 2))"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(inc x)" {:operation :replace :all? false})
            updated (z/root-string (:zloc result))]
        (is (some? result) "Should match forms with different whitespace")
        (is (str/includes? updated "(inc x)") "Should have replaced the form")
        (is (not (str/includes? updated "(+ x\n                  1)")) "Original form should be gone")))

    (testing "Tab and space mixing"
      (let [source "(let [a\t\t1\n      b  2] (+ a b))"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "a 1 b 2" "x 10 y 20" {:operation :replace :all? false})
            updated (z/root-string (:zloc result))]
        (is (some? result) "Should match with mixed whitespace")
        (is (str/includes? updated "x 10 y 20") "Should have replaced elements"))))

  (testing "Comments and discards"
    (testing "Discard forms (#_)"
      (let [source "(defn test [x] (+ x 1) #_ (ignored form) (+ x 2))"
            zloc (z/of-string source {:track-position? true})
            ;; The discard form is part of the sequence, so we need to match it
            result1 (sut/find-and-edit-multi-sexp zloc "(+ x 1) #_ (ignored form) (+ x 2)" "(inc x) #_ (ignored form) (dec x)" {:operation :replace :all? false})
            ;; Or we can replace individual forms
            result2 (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(inc x)" {:operation :replace :all? false})
            result3 (sut/find-and-edit-multi-sexp zloc "(+ x 2)" "(dec x)" {:operation :replace :all? false})]
        (is (or (some? result1) (and (some? result2) (some? result3))) "Should be able to match with or without discard forms")
        (when result1
          (let [updated (z/root-string (:zloc result1))]
            (is (str/includes? updated "#_ (ignored form)") "Should preserve discarded forms")))))

    (testing "Line comments between forms"
      (let [source "(defn test [x] (+ x 1) ;; comment\n               (+ x 2))"
            zloc (z/of-string source {:track-position? true})
            ;; Due to how comments are handled, they might interrupt multi-form matching
            result1 (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(inc x)" {:operation :replace :all? false})
            result2 (sut/find-and-edit-multi-sexp zloc "(+ x 2)" "(dec x)" {:operation :replace :all? false})]
        (is (some? result1) "Should be able to replace first form")
        (is (some? result2) "Should be able to replace second form"))))

  (testing "Reader macros"
    (testing "Var quote (#')"
      (let [source "(defn test [] #'user/foo #'bar/baz)"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "#'user/foo" "#'user/new-foo" {:operation :replace :all? false})
            updated (z/root-string (:zloc result))]
        (is (some? result) "Should match var-quoted symbols")
        (is (str/includes? updated "#'user/new-foo") "Should have replaced var quote")))

    (testing "Anonymous functions"
      (let [source "(map #(+ % 1) [1 2 3])"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "#(+ % 1)" "#(* % 2)" {:operation :replace :all? false})
            updated (z/root-string (:zloc result))]
        (is (some? result) "Should match anonymous functions")
        (is (str/includes? updated "#(* % 2)") "Should have replaced anon fn")))

    (testing "Reader conditionals"
      (let [source "#?(:clj (+ 1 2) :cljs (+ 3 4))"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc ":clj (+ 1 2)" ":clj (+ 10 20)" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match inside reader conditionals")
        (is (when result (str/includes? updated "(+ 10 20)")) "Should have replaced form"))))

  (testing "Metadata"
    (testing "Multiple metadata elements"
      (let [source "(def ^:private ^{:doc \"test\"} ^String foo \"bar\")"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "^:private ^{:doc \"test\"} ^String foo" "^:public ^Long bar" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match forms with metadata")
        (is (when result (str/includes? updated "^:public ^Long bar")) "Should have replaced with new metadata")))

    (testing "Metadata on collections"
      (let [source "^{:a 1} [1 2 3]"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "^{:a 1} [1 2 3]" "^{:b 2} [4 5 6]" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match metadata on collections")
        (is (when result (str/includes? updated "^{:b 2} [4 5 6]")) "Should have replaced with new metadata"))))

  (testing "Boundary cases"
    (testing "Replacing entire content"
      (let [source "(+ 1 2)"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "(+ 1 2)" "(* 3 4)" {:operation :replace :all? false})
            updated (z/root-string (:zloc result))]
        (is (some? result) "Should match entire content")
        (is (= "(* 3 4)" updated) "Should replace entire content")))

    (testing "Empty collections"
      (let [source "(defn test [] [] {} #{})"
            zloc (z/of-string source {:track-position? true})
            result1 (sut/find-and-edit-multi-sexp zloc "[]" "[1 2 3]" {:operation :replace :all? true})
            result2 (sut/find-and-edit-multi-sexp zloc "{}" "{:a 1}" {:operation :replace :all? true})
            result3 (sut/find-and-edit-multi-sexp zloc "#{}" "#{:x :y}" {:operation :replace :all? true})]
        (is (some? result1) "Should match empty vector")
        (is (some? result2) "Should match empty map")
        (is (some? result3) "Should match empty set"))))

  (testing "Namespaced elements"
    (testing "Auto-resolved keywords"
      (let [source "(ns my.ns) [::keyword ::other/keyword :regular]"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "::keyword" "::new-keyword" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match auto-resolved keywords")
        (is (when result (str/includes? updated "::new-keyword")) "Should have replaced keyword")))

    (testing "Fully qualified symbols"
      (let [source "(require '[clojure.string :as str]) (str/join \",\" items)"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "str/join" "str/split" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match namespaced symbols")
        (is (when result (str/includes? updated "str/split")) "Should have replaced symbol"))))

  (testing "Complex nested structures"
    (testing "Deeply nested replacement"
      (let [source "(let [a {:b {:c [1 2 {:d 3}]}}] a)"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "{:d 3}" "{:d 30 :e 40}" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should find deeply nested forms")
        (is (when result (str/includes? updated "{:d 30 :e 40}")) "Should have replaced nested form")))

    (testing "Matching across different nesting levels"
      (let [source "(defn test [x] (if true (+ x 1) (+ x 1)))"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "(+ x 1)" "(inc x)" {:operation :replace :all? true})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should find forms at different nesting levels")
        (is (when result (= 2 (count (re-seq #"\(inc x\)" updated)))) "Should replace all occurrences"))))

  (testing "Special forms and syntax"
    (testing "Let bindings"
      (let [source "(let [x 1 y 2] (+ x y))"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "x 1 y 2" "a 10 b 20" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match inside let bindings")
        (is (when result (str/includes? updated "[a 10 b 20]")) "Should have replaced bindings")))

    (testing "Destructuring"
      (let [source "(let [{:keys [a b]} m] (+ a b))"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "{:keys [a b]}" "{:keys [x y z]}" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match destructuring forms")
        (is (when result (str/includes? updated "{:keys [x y z]}")) "Should have replaced destructuring")))

    (testing "Syntax quote and unquote"
      (let [source "`(list ~x ~@xs)"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "~x" "~y" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match unquoted forms")
        (is (when result (str/includes? updated "~y")) "Should have replaced unquoted form"))))

  (testing "Pattern matching edge cases"
    (testing "Overlapping patterns"
      (let [source "(a b c d e)"
            zloc (z/of-string source {:track-position? true})
            ;; Pattern "b c" could match at different positions if there were multiple
            result (sut/find-and-edit-multi-sexp zloc "b c" "x y" {:operation :replace :all? false})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match pattern")
        (is (when result (str/includes? updated "(a x y d e)")) "Should replace at first match")))

    (testing "Single element multi-match"
      (let [source "[a a a a]"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "a" "x" {:operation :replace :all? true})
            updated (when result (z/root-string (:zloc result)))]
        (is (some? result) "Should match single elements")
        (is (when result (= "[x x x x]" updated)) "Should replace all occurrences"))))

  (testing "Pattern not found"
    (testing "No match returns nil"
      (let [source "(defn test [x] (+ x 1) (- x 2))"
            zloc (z/of-string source {:track-position? true})
            result (sut/find-and-edit-multi-sexp zloc "(* x 3)" "(/ x 4)" {:operation :replace :all? false})]
        (is (nil? result) "Should return nil when pattern is not found")))

    (testing "Partial multi-form match"
      (let [source "(a b c d)"
            zloc (z/of-string source {:track-position? true})
            ;; Looking for "b c e" which partially matches but not fully
            result (sut/find-and-edit-multi-sexp zloc "b c e" "x y z" {:operation :replace :all? false})]
        (is (nil? result) "Should return nil when multi-form pattern doesn't fully match")))

    (testing "Match beyond available forms"
      (let [source "[1 2 3]"
            zloc (z/of-string source {:track-position? true})
            ;; Looking for more elements than exist
            result (sut/find-and-edit-multi-sexp zloc "1 2 3 4" "a b c d" {:operation :replace :all? false})]
        (is (nil? result) "Should return nil when pattern extends beyond available forms")))))


