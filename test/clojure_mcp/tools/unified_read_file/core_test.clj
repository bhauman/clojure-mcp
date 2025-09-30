(ns clojure-mcp.tools.unified-read-file.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.tools.unified-read-file.core :as read-file-core]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Setup test fixtures
(def ^:dynamic *test-dir* nil)
(def ^:dynamic *test-file* nil)
(def ^:dynamic *large-test-file* nil)

(defn create-test-files-fixture [f]
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir") "clojure-mcp-test")
        test-file (io/file test-dir "test-file.txt")
        large-file (io/file test-dir "large-test-file.txt")]

    ;; Create test directory
    (.mkdirs test-dir)

    ;; Create small test file
    (spit test-file "Line 1\nLine 2\nLine 3\nLine 4\nLine 5")

    ;; Create large test file with 100 lines
    (with-open [w (io/writer large-file)]
      (doseq [i (range 1 101)]
        (.write w (str "This is line " i " of the test file.\n"))))

    ;; Bind dynamic vars for test
    (binding [*test-dir* test-dir
              *test-file* test-file
              *large-test-file* large-file]
      (try
        (f)
        (finally
          ;; Clean up
          (doseq [file [test-file large-file]]
            (when (.exists file)
              (.delete file)))
          (.delete test-dir))))))

(use-fixtures :each create-test-files-fixture)

(deftest read-file-test
  (testing "Reading a small file entirely"
    (let [result (read-file-core/read-file (.getPath *test-file*) 0 1000)]
      (is (map? result))
      (is (contains? result :content))
      (is (not (:truncated? result)))
      (is (= 5 (count (str/split (:content result) #"\n"))))
      (is (= (.getAbsolutePath *test-file*) (:path result)))))

  (testing "Reading with offset"
    (let [result (read-file-core/read-file (.getPath *test-file*) 2 1000)]
      (is (map? result))
      (is (= 3 (count (str/split (:content result) #"\n"))))
      (is (str/starts-with? (:content result) "Line 3"))
      (is (not (:truncated? result)))))

  (testing "Reading with limit"
    (let [result (read-file-core/read-file (.getPath *test-file*) 0 2)]
      (is (map? result))
      (is (= 2 (count (str/split (:content result) #"\n"))))
      (is (str/starts-with? (:content result) "Line 1"))
      (is (:truncated? result))
      (is (= "max-lines" (:truncated-by result)))
      (is (= 2 (:line-count result)))
      (is (= 5 (:total-line-count result)))))

  (testing "Reading with offset and limit"
    (let [result (read-file-core/read-file (.getPath *test-file*) 1 2)]
      (is (map? result))
      (is (= 2 (count (str/split (:content result) #"\n"))))
      (is (str/starts-with? (:content result) "Line 2"))
      (is (:truncated? result))
      ;; total-line-count should be total lines in file, not remaining from offset
      (is (= 5 (:total-line-count result)))))

  (testing "Reading with line length limit"
    (let [result (read-file-core/read-file (.getPath *large-test-file*) 0 5 :max-line-length 10)]
      (is (map? result))
      (is (= 5 (count (str/split (:content result) #"\n"))))
      (is (every? #(str/includes? % "...") (str/split (:content result) #"\n")))
      (is (:line-lengths-truncated? result))
      (is (= 100 (:total-line-count result))))))

(deftest read-file-error-test
  (testing "Reading non-existent file"
    (let [result (read-file-core/read-file (.getPath (io/file *test-dir* "nonexistent.txt")) 0 1000)]
      (is (map? result))
      (is (contains? result :error))
      (is (str/includes? (:error result) "does not exist"))))

  (testing "Reading a directory instead of a file"
    (let [result (read-file-core/read-file (.getPath *test-dir*) 0 1000)]
      (is (map? result))
      (is (contains? result :error))
      (is (str/includes? (:error result) "is not a file")))))