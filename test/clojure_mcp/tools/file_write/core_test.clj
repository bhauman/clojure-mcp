(ns clojure-mcp.tools.file-write.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.config :as config]
   [clojure-mcp.tools.file-write.core :as file-write-core]
   [clojure-mcp.tools.test-utils :as test-utils]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Setup test fixtures
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-clj-file* nil)
(def ^:dynamic *test-txt-file* nil)

(defn create-test-files-fixture [f]
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir") "clojure-mcp-test")
        test-clj-file (io/file test-dir "test-file.clj")
        test-txt-file (io/file test-dir "test-file.txt")]

    ;; Create test directory
    (.mkdirs test-dir)

    ;; Create initial Clojure file with valid content
    (spit test-clj-file "(ns test.namespace)\n\n(defn test-function [x]\n  (* x 2))")

    ;; Create initial text file
    (spit test-txt-file "This is a test file.\nIt has multiple lines.\nFor testing purposes.")

    ;; Bind dynamic vars for test
    (binding [*test-dir* test-dir
              *test-clj-file* test-clj-file
              *test-txt-file* test-txt-file]
      (config/set-config! test-utils/*nrepl-client-atom* :nrepl-user-dir test-dir)
      (try
        (f)
        (finally
          ;; Clean up
          (doseq [file [test-clj-file test-txt-file]]
            (when (.exists file)
              (.delete file)))
          (.delete test-dir))))))

(use-fixtures :once test-utils/test-nrepl-fixture)
(use-fixtures :each create-test-files-fixture)

(deftest is-clojure-file-test
  (testing "Detecting Clojure file extensions"
    (is (file-write-core/is-clojure-file? "test.clj"))
    (is (file-write-core/is-clojure-file? "test.cljs"))
    (is (file-write-core/is-clojure-file? "test.cljc"))
    (is (file-write-core/is-clojure-file? "test.edn"))
    (is (file-write-core/is-clojure-file? "test.bb"))
    (is (file-write-core/is-clojure-file? "/path/to/file.clj"))
    (is (file-write-core/is-clojure-file? "C:\\Path\\To\\File.CLJ")) ; Case insensitive
    (is (not (file-write-core/is-clojure-file? "test.txt")))
    (is (not (file-write-core/is-clojure-file? "test.md")))
    (is (not (file-write-core/is-clojure-file? "test.js"))))

  (testing "Detecting Babashka shebang"
    (let [tmp (io/file *test-dir* "script.sh")]
      (spit tmp "#!/usr/bin/env bb\n(println :hi)")
      (is (file-write-core/is-clojure-file? (.getPath tmp)))
      (.delete tmp))))

(deftest write-text-file-test
  (testing "Creating a new text file"
    (let [new-file (io/file *test-dir* "new-text-file.txt")
          content "This is a brand new file."
          result (file-write-core/write-text-file (.getPath new-file) content)]
      (is (not (:error result)))
      (is (= "create" (:type result)))
      (is (= (.getPath new-file) (:file-path result)))
      (is (str/blank? (:diff result))) ; No diff for new files
      (is (.exists new-file))
      (is (= content (slurp new-file)))
      (.delete new-file)))

  (testing "Updating an existing text file"
    (let [path (.getPath *test-txt-file*)
          original-content (slurp *test-txt-file*)
          new-content "This is updated content.\nWith new lines."
          result (file-write-core/write-text-file path new-content)]
      (is (not (:error result)))
      (is (= "update" (:type result)))
      (is (= path (:file-path result)))
      (is (not (str/blank? (:diff result)))) ; Should have a diff
      (is (= new-content (slurp *test-txt-file*))))))

(deftest write-clojure-file-test
  (testing "Creating a new Clojure file"
    (let [new-file (io/file *test-dir* "new-file.clj")
          content "(ns new.namespace)\n\n(defn new-function [x]\n  (+ x 10))"
          result (file-write-core/write-clojure-file
                  test-utils/*nrepl-client-atom*
                  (.getPath new-file)
                  content)]
      (is (not (:error result)))
      (is (= "create" (:type result)))
      (is (= (.getPath new-file) (:file-path result)))
      (is (.exists new-file))
      (.delete new-file)))

  (testing "Updating an existing Clojure file"
    (let [path (.getPath *test-clj-file*)
          original-content (slurp *test-clj-file*)
          new-content "(ns test.namespace)\n\n(defn test-function [x]\n  (+ x 10))"
          result (file-write-core/write-clojure-file
                  test-utils/*nrepl-client-atom*
                  path
                  new-content)]
      (is (not (:error result)))
      (is (= "update" (:type result)))
      (is (= path (:file-path result)))
      (is (not (str/blank? (:diff result)))) ; Should have a diff
      (is (not (= original-content (slurp *test-clj-file*)))))) ; Content should be updated

  (testing "Formatting Clojure code during write"
    (let [path (.getPath *test-clj-file*)
          unformatted-content "(ns test.namespace)( defn poorly-formatted-fn[x]( + x 5) )"
          result (file-write-core/write-clojure-file
                  test-utils/*nrepl-client-atom*
                  path
                  unformatted-content)]
      (is (not (:error result)))
      (is (= "update" (:type result)))
      (is (not (str/includes? (slurp *test-clj-file*) "poorly-formatted-fn[x]")))
      (is (str/includes? (slurp *test-clj-file*) "poorly-formatted-fn [x]"))))

  (testing "Auto-repairs missing closing parenthesis"
    (let [path (.getPath *test-clj-file*)
          content-with-missing-paren "(ns test.namespace)\n\n(defn repaired-function [x]\n  (+ x 10)"
          result (file-write-core/write-clojure-file
                  test-utils/*nrepl-client-atom*
                  path
                  content-with-missing-paren)]
      (is (not (:error result)))
      (is (= "update" (:type result)))
      (let [saved-content (slurp *test-clj-file*)]
        (is (not (= content-with-missing-paren saved-content)))
        (is (str/includes? saved-content "repaired-function [x]"))
        (is (str/includes? saved-content "(+ x 10))"))))) ; Note the extra closing paren

  (testing "Auto-repairs unbalanced brackets"
    (let [path (.getPath *test-clj-file*)
          content-with-mismatched-brackets "(ns test.namespace)\n\n(defn bracket-fn [x]\n  (let [y (+ x 1)]\n    (println y]))"
          result (file-write-core/write-clojure-file
                  test-utils/*nrepl-client-atom*
                  path
                  content-with-mismatched-brackets)]
      (is (not (:error result)))
      (is (= "update" (:type result)))
      (let [saved-content (slurp *test-clj-file*)]
        (is (not (= content-with-mismatched-brackets saved-content)))
        (is (str/includes? saved-content "bracket-fn [x]"))
        (is (not (str/includes? saved-content "println y]"))))))

  (testing "Still fails with non-repairable syntax errors"
    (let [path (.getPath *test-clj-file*)
          content-with-syntax-error "(ns test.namespace)\n\n(defn broken-function a123 [x 11)\n  (+ x 10))"
          original-content (slurp *test-clj-file*)
          result (file-write-core/write-clojure-file
                  test-utils/*nrepl-client-atom*
                  path
                  content-with-syntax-error)]
      ;; The test should fail with a specific error
      (is (:error result) "Should have error for non-repairable syntax error")
      (when (:message result)
        (is (or (str/includes? (:message result) "Syntax errors")
                (str/includes? (:message result) "Delimiter errors"))))
      ;; File should not be modified
      (is (= original-content (slurp *test-clj-file*))))))

(deftest write-file-test
  (testing "Write dispatcher for Clojure files"
    (let [clj-file (io/file *test-dir* "dispatch-test.clj")
          content "(ns dispatch.test)"
          result (file-write-core/write-file test-utils/*nrepl-client-atom*
                                             (.getPath clj-file)
                                             content)]
      (is (not (:error result)))
      (is (= "create" (:type result)))
      (is (.exists clj-file))
      (.delete clj-file)))

  (testing "Write dispatcher for text files"
    (let [txt-file (io/file *test-dir* "dispatch-test.txt")
          content "Plain text content"
          result (file-write-core/write-file test-utils/*nrepl-client-atom*
                                             (.getPath txt-file)
                                             content)]
      (is (not (:error result)))
      (is (= "create" (:type result)))
      (is (.exists txt-file))
      (.delete txt-file))))
