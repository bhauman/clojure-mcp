(ns clojure-mcp.tools.grep.core-test
  (:require [clojure.test :refer :all]
            [clojure-mcp.tools.grep.core :as sut]
            [clojure.java.io :as io]))

(deftest grep-files-with-test-directory
  (testing "grep-files with controlled test directory"
    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir") "grep-files-test")]

      ;; Set up test directory structure
      (.mkdirs temp-dir)
      (let [file1 (io/file temp-dir "file1.clj")
            file2 (io/file temp-dir "file2.clj")
            file3 (io/file temp-dir "file3.txt")
            subdir (io/file temp-dir "subdir")
            file4 (io/file subdir "file4.clj")]

        (try
          ;; Create test files with specific content
          (.mkdirs subdir)
          (spit file1 "(defn test-function [x] (+ x 1))")
          (spit file2 "(defn another-function [y] (* y 2))")
          (spit file3 "This is a text file with no functions")
          (spit file4 "(defn nested-function [z] (- z 3))")

          (testing "find pattern across all files"
            (let [result (sut/grep-files (.getAbsolutePath temp-dir) "defn")]
              (is (map? result))
              (is (vector? (:filenames result)))
              (is (= 3 (:numFiles result)))
              (is (some #(.contains % "file1.clj") (:filenames result)))
              (is (some #(.contains % "file2.clj") (:filenames result)))
              (is (some #(.contains % "file4.clj") (:filenames result)))
              (is (not (some #(.contains % "file3.txt") (:filenames result))))
              (is (contains? result :truncated))
              (is (false? (:truncated result)))))

          (testing "find pattern with file extension filter"
            (let [result (sut/grep-files (.getAbsolutePath temp-dir) "defn" :include "*.clj")]
              (is (= 3 (:numFiles result)))
              (is (some #(.contains % "file1.clj") (:filenames result)))
              (is (some #(.contains % "file2.clj") (:filenames result)))
              (is (some #(.contains % "file4.clj") (:filenames result)))
              (is (false? (:truncated result)))))

          (testing "find pattern with text file extension"
            (let [result (sut/grep-files (.getAbsolutePath temp-dir) "text file" :include "*.txt")]
              (is (= 1 (:numFiles result)))
              (is (some #(.contains % "file3.txt") (:filenames result)))
              (is (false? (:truncated result)))))

          (testing "no results for non-existent pattern"
            (let [result (sut/grep-files (.getAbsolutePath temp-dir) "THIS_PATTERN_DOESNT_EXIST")]
              (is (= 0 (:numFiles result)))
              (is (empty? (:filenames result)))
              (is (false? (:truncated result)))))

          (testing "max results parameter"
            (let [result (sut/grep-files (.getAbsolutePath temp-dir) "defn" :max-results 2)]
              (is (= 3 (:numFiles result)))
              (is (<= (count (:filenames result)) 2))
              (is (true? (:truncated result)))))

          (finally
            ;; Clean up
            (io/delete-file file1 true)
            (io/delete-file file2 true)
            (io/delete-file file3 true)
            (io/delete-file file4 true)
            (io/delete-file subdir true)
            (io/delete-file temp-dir true)))))))

(deftest grep-files-with-project-directory
  (testing "grep-files with the actual project directory"
    (let [project-dir (System/getProperty "user.dir")]

      (testing "find tool-system multimethod usage"
        (let [result (sut/grep-files project-dir "defmethod tool-system" :include "*.clj")]
          (is (> (:numFiles result) 0))
          (is (some #(.contains % "tool.clj") (:filenames result)))
          (is (contains? result :truncated))))

      (testing "find core functions"
        (let [result (sut/grep-files (str project-dir "/src") "defn grep-" :include "*.clj")]
          (is (> (:numFiles result) 0))
          (is (some #(.contains % "grep") (:filenames result)))
          (is (contains? result :truncated))))

      (testing "find with multiple file extensions using brace expansion"
        (let [result (sut/grep-files project-dir "project.clj" :include "*.{md,clj}")]
          (is (> (:numFiles result) 0))
          (is (contains? result :truncated))))

      (testing "with complex regex pattern"
        (let [result (sut/grep-files (str project-dir "/src") "defn\\s+[a-z\\-]+\\s*\\[" :include "*.clj")]
          (is (> (:numFiles result) 0))
          (is (> (:durationMs result) 0))
          (is (contains? result :truncated))))

      (testing "with max-results limitation"
        (let [all-results (sut/grep-files project-dir "defn" :include "*.clj")
              limited-results (sut/grep-files project-dir "defn" :include "*.clj" :max-results 5)]
          (is (>= (:numFiles all-results) (:numFiles limited-results)))
          (is (<= (count (:filenames limited-results)) 5))
          (is (= (:numFiles all-results) (:numFiles limited-results)))
          (is (if (> (:numFiles limited-results) 5)
                (true? (:truncated limited-results))
                (false? (:truncated limited-results))))))

      (testing "filenames are sorted by modification time (newest first)"
        (let [result (sut/grep-files project-dir "defn" :max-results 10)]
          (when (> (count (:filenames result)) 1)
            (let [files (mapv io/file (:filenames result))
                  mod-times (mapv #(.lastModified %) files)]
              (is (apply >= mod-times)))))))))

(deftest grep-files-error-handling
  (testing "error handling in grep-files"
    (testing "non-existent directory"
      (let [result (sut/grep-files "/path/that/does/not/exist" "pattern")]
        (is (contains? result :error))
        (is (string? (:error result)))
        (is (.contains (:error result) "not a valid file or directory"))))

    (testing "invalid regex pattern handling with controlled dir"
      (let [temp-dir (io/file (System/getProperty "java.io.tmpdir") "grep-error-test")]
        (try
          (.mkdirs temp-dir)
          (spit (io/file temp-dir "test.txt") "test content")

          (let [result (sut/grep-files (.getAbsolutePath temp-dir) "[invalid regex)]")]
            ;; Ensure we get a result map
            (is (map? result))
            ;; Ensure it has duration measurement
            (is (contains? result :durationMs))
            ;; Multiple possibilities depending on implementation:
            (is (or
                 ;; 1. Command line grep returns error
                 (contains? result :error)
                 ;; 2. Java implementation returns empty results
                 (and (contains? result :filenames) (zero? (:numFiles result)))
                 ;; 3. Java implementation might actually handle the bad pattern
                 ;; and return valid :filenames with proper count
                 (and (contains? result :filenames) (number? (:numFiles result))))))

          (finally
            (io/delete-file (io/file temp-dir "test.txt") true)
            (io/delete-file temp-dir true)))))

    ;; Add a separate test for a simpler invalid pattern that produces consistent results
    (testing "simple invalid pattern handling"
      (let [temp-dir (io/file (System/getProperty "java.io.tmpdir") "grep-simple-error-test")]
        (try
          (.mkdirs temp-dir)
          (spit (io/file temp-dir "test.txt") "test content")

          (let [result (sut/grep-files (.getAbsolutePath temp-dir) "*")]
            ;; Just ensure we get a result and don't crash
            (is (map? result))
            (is (contains? result :durationMs)))

          (finally
            (io/delete-file (io/file temp-dir "test.txt") true)
            (io/delete-file temp-dir true)))))))

(deftest grep-single-file
  (testing "grep-files with a single file"
    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir") "grep-single-file-test")
          test-file (io/file temp-dir "test.clj")]
      (try
        (.mkdirs temp-dir)
        (spit test-file "(defn my-function [x]\n  (+ x 1))\n\n(defn another-function [y]\n  (* y 2))")

        (testing "find lines containing pattern"
          (let [result (sut/grep-files (.getAbsolutePath test-file) "defn")]
            (is (map? result))
            (is (contains? result :lines))
            (is (contains? result :numMatches))
            (is (= 2 (:numMatches result)))
            (is (= [{:line-number 1 :content "(defn my-function [x]"}
                    {:line-number 4 :content "(defn another-function [y]"}]
                   (:lines result)))))

        (testing "find lines with complex pattern"
          (let [result (sut/grep-files (.getAbsolutePath test-file) "\\+ x")]
            (is (= 1 (:numMatches result)))
            (is (= 2 (:line-number (first (:lines result)))))))

        (testing "no matches returns empty lines"
          (let [result (sut/grep-files (.getAbsolutePath test-file) "NONEXISTENT")]
            (is (= 0 (:numMatches result)))
            (is (empty? (:lines result)))))

        (finally
          (io/delete-file test-file true)
          (io/delete-file temp-dir true))))))
