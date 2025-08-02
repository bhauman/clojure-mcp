(ns clojure-mcp.tools.form-edit.update-sexp-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure-mcp.tools.form-edit.tool :as sut]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:dynamic *test-dir* (atom nil))
(def ^:dynamic *test-file* (atom nil))
(def ^:dynamic *client-atom* nil)
(defn create-test-files-fixture [f]
  ;; Create a temporary directory
  (let [test-dir (.toFile (java.nio.file.Files/createTempDirectory
                           "clojure-mcp-update-sexp-test"
                           (make-array java.nio.file.attribute.FileAttribute 0)))]
    (reset! *test-dir* test-dir)
    (reset! *test-file* (io/file test-dir "test-update-sexp.clj"))

    ;; Create a client atom for this test
    (try
      (binding [*client-atom* (atom {:clojure-mcp.config/config {:allowed-directories [(.getCanonicalPath test-dir)]
                                                                 :nrepl-user-dir (.getCanonicalPath test-dir)}})]
        (f))
      (finally
        ;; Clean up
        (doseq [f (reverse (file-seq test-dir))]
          (.delete f))))))

(use-fixtures :each create-test-files-fixture)

(defn get-file-path []
  (.getCanonicalPath @*test-file*))

(defn register-file-timestamp []
  "Updates the timestamp for the test file to mark it as read."
  (let [file-path (get-file-path)]
    (file-timestamps/update-file-timestamp-to-current-mtime! *client-atom* file-path)
    ;; Small delay to ensure timestamps differ if modified
    (Thread/sleep 25)
    file-path))

(defn create-test-file [content]
  (spit (get-file-path) content)
  (register-file-timestamp))

(deftest clojure-update-sexp-multi-op-test
  (testing "Multi-op tool with replace operation"
    (let [_ (create-test-file "(defn test-fn [x] (+ x 1) (- y 2))")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(+ x 1)"
                  :new_form "(+ x 10)"
                  :operation "replace"
                  :replace_all false}
          validated (tool-system/validate-inputs sexp-tool inputs)
          result (tool-system/execute-tool sexp-tool validated)
          formatted (tool-system/format-results sexp-tool result)
          file-content (slurp (get-file-path))]

      (is (= "clojure_update_sexp" (tool-system/tool-name sexp-tool)) "Tool name should be clojure_update_sexp")
      (is (false? (:error formatted)) "Should not have an error")
      (is (str/includes? file-content "(+ x 10)") "File should contain the replaced expression")
      (is (not (str/includes? file-content "(+ x 1)")) "Original expression should be gone")))

  (testing "Multi-op tool with insert_before operation"
    (let [_ (create-test-file "(defn test-fn [x] (+ x 1) (- y 2))")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(+ x 1)"
                  :new_form "(println \"Before addition\")"
                  :operation "insert_before"
                  :replace_all false}
          validated (tool-system/validate-inputs sexp-tool inputs)
          result (tool-system/execute-tool sexp-tool validated)
          formatted (tool-system/format-results sexp-tool result)
          file-content (slurp (get-file-path))]

      (is (false? (:error formatted)) "Should not have an error")
      (is (str/includes? file-content "(println \"Before addition\")") "File should contain the inserted form")
      (is (str/includes? file-content "(+ x 1)") "Original expression should still be there")
      ;; Check order - println should come before (+ x 1)
      (let [println-idx (str/index-of file-content "(println \"Before addition\")")
            plus-idx (str/index-of file-content "(+ x 1)")]
        (is (< println-idx plus-idx) "Inserted form should come before the original"))))

  (testing "Multi-op tool with insert_after operation"
    (let [_ (create-test-file "(defn test-fn [x] (+ x 1) (- y 2))")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(+ x 1)"
                  :new_form "(println \"After addition\" x)"
                  :operation "insert_after"
                  :replace_all false}
          validated (tool-system/validate-inputs sexp-tool inputs)
          result (tool-system/execute-tool sexp-tool validated)
          formatted (tool-system/format-results sexp-tool result)
          file-content (slurp (get-file-path))]

      (is (false? (:error formatted)) "Should not have an error")
      (is (str/includes? file-content "(println \"After addition\" x)") "File should contain the inserted form")
      (is (str/includes? file-content "(+ x 1)") "Original expression should still be there")
      ;; Check order - println should come after (+ x 1)
      (let [println-idx (str/index-of file-content "(println \"After addition\" x)")
            plus-idx (str/index-of file-content "(+ x 1)")]
        (is (> println-idx plus-idx) "Inserted form should come after the original"))))

  (testing "Multi-op tool with replace_all true"
    (let [_ (create-test-file "(defn test-fn [x] (inc x) (inc x) (inc x) (dec x))")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(inc x)"
                  :new_form "(+ x 1)"
                  :operation "replace"
                  :replace_all true}
          validated (tool-system/validate-inputs sexp-tool inputs)
          result (tool-system/execute-tool sexp-tool validated)
          formatted (tool-system/format-results sexp-tool result)
          file-content (slurp (get-file-path))]

      (is (false? (:error formatted)) "Should not have an error")
      (is (= 3 (count (re-seq #"\(\+ x 1\)" file-content))) "Should have 3 replacements")
      (is (not (str/includes? file-content "(inc x)")) "Original expressions should be gone")
      (is (str/includes? file-content "(dec x)") "Non-matching forms should be preserved"))))

(deftest clojure-update-sexp-validation-test
  (testing "Multi-op tool requires operation parameter"
    (let [_ (create-test-file "(defn test-fn [x] x)")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(defn test-fn [x] x)"
                  :new_form "(defn new-fn [x] x)"}]
      ;; Missing operation parameter
      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs sexp-tool inputs))
          "Should throw exception when operation is missing")))

  (testing "Invalid operation value"
    (let [_ (create-test-file "(defn test-fn [x] x)")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(defn test-fn [x] x)"
                  :new_form "(defn new-fn [x] x)"
                  :operation "delete"}]
      ;; Invalid operation
      (is (thrown? clojure.lang.ExceptionInfo
                   (tool-system/validate-inputs sexp-tool inputs))
          "Should throw exception for invalid operation")))

  (testing "Non-multi-op tool doesn't require operation"
    (let [_ (create-test-file "(defn test-fn [x] x)")
          client-atom *client-atom*
          sexp-tool (sut/create-update-sexp-tool client-atom) ; Default multi-op false
          inputs {:file_path (get-file-path)
                  :match_form "(defn test-fn [x] x)"
                  :new_form "(defn new-fn [x] x)"}
          validated (tool-system/validate-inputs sexp-tool inputs)]
      ;; Should not throw, operation defaults to replace
      (is (= (get-file-path) (:file_path validated)))
      (is (nil? (:operation validated)) "Operation should not be in validated inputs"))))

(deftest clojure-update-sexp-backward-compatibility-test
  (testing "Tool without multi-op flag maintains backward compatibility"
    (let [_ (create-test-file "(defn old-fn [x] (+ x 1))")
          client-atom *client-atom*
          sexp-tool (sut/create-update-sexp-tool client-atom)
          inputs {:file_path (get-file-path)
                  :match_form "(+ x 1)"
                  :new_form "(* x 2)"
                  :replace_all false}
          validated (tool-system/validate-inputs sexp-tool inputs)
          result (tool-system/execute-tool sexp-tool validated)
          formatted (tool-system/format-results sexp-tool result)
          file-content (slurp (get-file-path))]

      (is (= "clojure_edit_replace_sexp" (tool-system/tool-name sexp-tool))
          "Tool name should be backward compatible")
      (is (false? (:error formatted)) "Should not have an error")
      (is (str/includes? file-content "(* x 2)") "File should contain the replaced expression")
      (is (not (str/includes? file-content "(+ x 1)")) "Original expression should be gone"))))

(deftest clojure-update-sexp-edge-cases-test
  (testing "Empty new_form with insert operations"
    (let [_ (create-test-file "(defn test-fn [x] (+ x 1))")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(+ x 1)"
                  :new_form ""
                  :operation "insert_before"
                  :replace_all false}]
      ;; Empty new_form with insert operations should be a no-op
      (let [validated (tool-system/validate-inputs sexp-tool inputs)
            result (tool-system/execute-tool sexp-tool validated)
            formatted (tool-system/format-results sexp-tool result)
            file-content (slurp (get-file-path))]
        (is (true? (:error formatted)) "Should indicate error for no match")
        (is (str/includes? file-content "(+ x 1)") "Original content should be unchanged"))))

  (testing "Multi-form patterns with insert operations"
    (let [_ (create-test-file "(defn test-fn [x] (let [a 1] (+ a x)) (- x 2))")
          client-atom *client-atom*
          sexp-tool (assoc (sut/create-update-sexp-tool client-atom) :multi-op true)
          inputs {:file_path (get-file-path)
                  :match_form "(let [a 1] (+ a x))"
                  :new_form "(println \"Complex form\")"
                  :operation "insert_after"
                  :replace_all false}
          validated (tool-system/validate-inputs sexp-tool inputs)
          result (tool-system/execute-tool sexp-tool validated)
          formatted (tool-system/format-results sexp-tool result)
          file-content (slurp (get-file-path))]

      (is (false? (:error formatted)) "Should not have an error")
      (is (str/includes? file-content "(println \"Complex form\")") "Should insert after complex form")
      (is (str/includes? file-content "(let [a 1] (+ a x))") "Original form should be preserved"))))
