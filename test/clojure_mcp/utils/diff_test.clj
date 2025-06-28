(ns clojure-mcp.utils.diff-test
  (:require
   [clojure.test :refer [deftest is testing run-tests]]
   [clojure.string :as str]
   [clojure-mcp.utils.diff :as diff]))

(deftest test-generate-unified-diff-basic
  (testing "Basic unified diff generation"
    (let [original "Hello world\nThis is line 2\nThis is line 3"
          revised "Hello world\nThis is modified line 2\nThis is line 3"
          result (diff/generate-unified-diff original revised)]
      (is (string? result) "Should return a string")
      (is (str/includes? result "--- original.txt") "Should include original filename")
      (is (str/includes? result "+++ revised.txt") "Should include revised filename")
      (is (str/includes? result "-This is line 2") "Should show removed line")
      (is (str/includes? result "+This is modified line 2") "Should show added line"))))

(deftest test-generate-unified-diff-identical-content
  (testing "Unified diff with identical content"
    (let [content "Same content\nOn multiple lines\nWith no changes"
          result (diff/generate-unified-diff content content)]
      (is (string? result) "Should return a string")
      ;; When there are no differences, the unified diff should be minimal
      (is (or (empty? result)
              (and (str/includes? result "--- original.txt")
                   (str/includes? result "+++ revised.txt")
                   (not (str/includes? result "@@ ")) ; No diff hunks
                   ))
          "Should indicate no differences or be empty"))))

(deftest test-generate-unified-diff-empty-strings
  (testing "Unified diff with empty strings"
    (let [result (diff/generate-unified-diff "" "")]
      (is (string? result) "Should return a string even for empty input"))))

(deftest test-generate-unified-diff-one-empty
  (testing "Unified diff with one empty string"
    (let [content "Some content here\nWith multiple lines"
          result1 (diff/generate-unified-diff "" content)
          result2 (diff/generate-unified-diff content "")]
      (is (string? result1) "Should handle empty original")
      (is (string? result2) "Should handle empty revised")
      (is (str/includes? result1 "+Some content here") "Should show all lines as added")
      (is (str/includes? result2 "-Some content here") "Should show all lines as removed"))))

(deftest test-generate-unified-diff-context-lines
  (testing "Unified diff with different context line settings"
    (let [original (str/join "\n" ["Line 1" "Line 2" "Line 3" "Line 4" "Line 5" "Line 6" "Line 7"])
          revised (str/join "\n" ["Line 1" "Line 2" "Modified Line 3" "Line 4" "Line 5" "Line 6" "Line 7"])
          result-default (diff/generate-unified-diff original revised)
          result-1-context (diff/generate-unified-diff original revised 1)
          result-5-context (diff/generate-unified-diff original revised 5)]

      (is (string? result-default) "Should work with default context")
      (is (string? result-1-context) "Should work with 1 context line")
      (is (string? result-5-context) "Should work with 5 context lines")

      ;; The number of context lines affects the diff output format
      (is (str/includes? result-default "@@ ") "Should contain diff hunk marker")
      (is (str/includes? result-1-context "@@ ") "Should contain diff hunk marker")
      (is (str/includes? result-5-context "@@ ") "Should contain diff hunk marker"))))

(deftest test-generate-unified-diff-multiple-changes
  (testing "Unified diff with multiple changes"
    (let [original (str/join "\n" ["Keep this line"
                                   "Remove this line"
                                   "Keep this line too"
                                   "Also remove this"
                                   "Final line"])
          revised (str/join "\n" ["Keep this line"
                                  "Add this new line"
                                  "Keep this line too"
                                  "Replace with this"
                                  "Final line"])
          result (diff/generate-unified-diff original revised)]

      (is (string? result) "Should return a string")
      (is (str/includes? result "-Remove this line") "Should show first removal")
      (is (str/includes? result "+Add this new line") "Should show first addition")
      (is (str/includes? result "-Also remove this") "Should show second removal")
      (is (str/includes? result "+Replace with this") "Should show second addition"))))

(deftest test-generate-unified-diff-line-endings
  (testing "Unified diff handles different line endings"
    (let [original "Line 1\nLine 2\nLine 3"
          revised "Line 1\r\nLine 2\r\nLine 3\r\n" ; Windows line endings + extra newline
          result (diff/generate-unified-diff original revised)]
      (is (string? result) "Should handle mixed line endings"))))

(deftest test-generate-unified-diff-special-characters
  (testing "Unified diff with special characters"
    (let [original "Normal line\nLine with symbols: !@#$%^&*()\nAnother line"
          revised "Normal line\nLine with symbols: !@#$%^&*() MODIFIED\nAnother line"
          result (diff/generate-unified-diff original revised)]
      (is (string? result) "Should handle special characters")
      (is (str/includes? result "!@#$%^&*()") "Should preserve special characters in output"))))

(deftest test-generate-unified-diff-unicode
  (testing "Unified diff with Unicode characters"
    (let [original "Hello 世界\nUnicode: αβγδε\nEmoji: 🎉🚀"
          revised "Hello 世界 modified\nUnicode: αβγδε\nEmoji: 🎉🚀✨"
          result (diff/generate-unified-diff original revised)]
      (is (string? result) "Should handle Unicode characters")
      (is (str/includes? result "世界") "Should preserve Unicode in output"))))

(deftest test-generate-unified-diff-large-content
  (testing "Unified diff with larger content"
    (let [original (str/join "\n" (map #(str "Original line " %) (range 1 21)))
          revised (str/join "\n" (concat (map #(str "Original line " %) (range 1 11))
                                         ["Modified line 11"]
                                         (map #(str "Original line " %) (range 12 21))))
          result (diff/generate-unified-diff original revised)]
      (is (string? result) "Should handle larger content")
      (is (str/includes? result "-Original line 11") "Should show removed line")
      (is (str/includes? result "+Modified line 11") "Should show added line"))))

(deftest test-generate-unified-diff-format-validation
  (testing "Unified diff output format validation"
    (let [original "Line A\nLine B\nLine C"
          revised "Line A\nModified Line B\nLine C"
          result (diff/generate-unified-diff original revised)
          lines (str/split-lines result)]

      (is (>= (count lines) 4) "Should have at least header and diff content")

      ;; Check basic unified diff format
      (let [header-lines (take 2 lines)]
        (is (str/starts-with? (first header-lines) "--- ") "First line should be original file marker")
        (is (str/starts-with? (second header-lines) "+++ ") "Second line should be revised file marker"))

      ;; Should contain hunk header
      (is (some #(str/starts-with? % "@@ ") lines) "Should contain at least one hunk header"))))

(deftest test-generate-unified-diff-arity-overloads
  (testing "Function works with both 2 and 3 arguments"
    (let [original "Test line 1\nTest line 2"
          revised "Test line 1\nModified test line 2"
          result-2-args (diff/generate-unified-diff original revised)
          result-3-args (diff/generate-unified-diff original revised 3)]

      (is (string? result-2-args) "Should work with 2 arguments")
      (is (string? result-3-args) "Should work with 3 arguments")
      (is (= result-2-args result-3-args) "Both should produce same result when context=3"))))

;; Utility function to run all tests in this namespace
(defn run-diff-tests
  "Run all diff utility tests"
  []
  (run-tests 'clojure-mcp.utils.diff-test))

;; Example usage demonstration
(comment
  ;; Run all tests
  (run-diff-tests)

  ;; Run specific test
  (test-generate-unified-diff-basic)

  ;; Example of testing the function directly
  (diff/generate-unified-diff
   "Original content\nLine 2\nLine 3"
   "Modified content\nLine 2\nLine 3")

  ;; Test with different context
  (diff/generate-unified-diff
   "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
   "Line 1\nModified Line 2\nLine 3\nLine 4\nLine 5"
   1) ; Only 1 context line
  )
