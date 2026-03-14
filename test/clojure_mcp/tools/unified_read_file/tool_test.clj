(ns clojure-mcp.tools.unified-read-file.tool-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.tools.test-utils :as test-utils :refer [*nrepl-client-atom*]]
   [clojure-mcp.tools.unified-read-file.tool :as unified-read-file-tool]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.config :as config]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; Setup test fixtures
(test-utils/apply-fixtures *ns*)

;; Setup test files
(def ^:dynamic *test-dir* nil)

(defn setup-test-files-fixture [f]
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir") "clojure-mcp-unified-read-test")]
    ;; Create test directory
    (.mkdirs test-dir)

    ;; Set allowed directories for path validation using config/set-config!
    (config/set-config! *nrepl-client-atom* :nrepl-user-dir (.getAbsolutePath test-dir))
    (config/set-config! *nrepl-client-atom* :allowed-directories [(.getAbsolutePath test-dir)])

    ;; Run test with fixtures bound
    (binding [*test-dir* test-dir]
      (try
        (f)
        (finally
          ;; Clean up all files recursively
          (when (.exists test-dir)
            (doseq [file (reverse (file-seq test-dir))]
              (.delete file))))))))

(use-fixtures :each setup-test-files-fixture)

(deftest non-existent-file-test
  (testing "Reading non-existent file returns error"
    (let [tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          non-existent-path (.getAbsolutePath (io/file *test-dir* "does-not-exist.clj"))]

      ;; Test with collapsed true
      (testing "collapsed mode"
        (let [result (tool-system/execute-tool
                      tool-instance
                      {:path non-existent-path
                       :collapsed true})]
          (is (:error result) "Should return an error for non-existent file")))

      ;; Test with collapsed false
      (testing "non-collapsed mode"
        (let [result (tool-system/execute-tool
                      tool-instance
                      {:path non-existent-path
                       :collapsed false})]
          (is (:error result) "Should return an error for non-existent file"))))))

(deftest collapsible-clojure-file-test
  (testing "Detecting collapsible Clojure file extensions"
    (is (unified-read-file-tool/collapsible-clojure-file? "test.clj"))
    (is (unified-read-file-tool/collapsible-clojure-file? "test.cljs"))
    (is (unified-read-file-tool/collapsible-clojure-file? "test.cljc"))
    (is (unified-read-file-tool/collapsible-clojure-file? "test.bb"))
    (is (unified-read-file-tool/collapsible-clojure-file? "/path/to/file.clj"))
    (let [tmp (io/file *test-dir* "script.sh")]
      (spit tmp "#!/usr/bin/env bb\n(println :hi)")
      (is (unified-read-file-tool/collapsible-clojure-file? (.getPath tmp)))
      (.delete tmp))
    (is (not (unified-read-file-tool/collapsible-clojure-file? "test.edn"))) ; EDN files not collapsible
    (is (not (unified-read-file-tool/collapsible-clojure-file? "test.txt")))
    (is (not (unified-read-file-tool/collapsible-clojure-file? "test.md")))
    (is (not (unified-read-file-tool/collapsible-clojure-file? "test.js")))))

(deftest format-raw-file-truncation-message-test
  (testing "Truncation message shows total line count, not file size"
    (let [result {:content "line1\nline2\nline3"
                  :path "/test/file.txt"
                  :size 118628  ; File size in bytes (should NOT be shown)
                  :line-count 3  ; Lines shown
                  :total-line-count 2000  ; Total lines in file (should be shown)
                  :truncated? true}
          formatted (unified-read-file-tool/format-raw-file result 2000)
          formatted-str (first formatted)]
      (is (re-find #"showing 3 of 2000 lines" formatted-str)
          "Should show total line count (2000), not file size (118628)")))

  (testing "Non-truncated file doesn't show truncation message"
    (let [result {:content "line1\nline2"
                  :path "/test/file.txt"
                  :size 1000
                  :line-count 2
                  :truncated? false}
          formatted (unified-read-file-tool/format-raw-file result 2000)
          formatted-str (first formatted)]
      (is (not (re-find #"truncated" formatted-str))
          "Should not show truncation message when not truncated"))))

;; --- Dash-to-underscore filename correction integration tests ---
;; The core correction logic is tested in valid_paths_test.clj.
;; These tests verify the read_file tool pipeline works with the correction.

(deftest dash-to-underscore-correction-test
  (testing "File with dashes requested, underscore version exists - reads successfully"
    (let [underscore-file (io/file *test-dir* "core_stuff.clj")
          _ (spit underscore-file "(ns core-stuff)\n(defn hello [] :world)")
          tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          dash-path (.getAbsolutePath (io/file *test-dir* "core-stuff.clj"))
          validated (tool-system/validate-inputs tool-instance {:path dash-path})
          result (tool-system/execute-tool tool-instance validated)
          formatted (tool-system/format-results tool-instance result)]
      ;; Should succeed (not error)
      (is (not (:error formatted)) "Should successfully read the corrected file")
      ;; Should contain the actual file content
      (is (some #(str/includes? % "core-stuff") (:result formatted))
          "Should contain the file content")
      (.delete underscore-file)))

  (testing "File with underscores requested directly - reads successfully"
    (let [underscore-file (io/file *test-dir* "core_stuff.clj")
          _ (spit underscore-file "(ns core-stuff)\n(defn hello [] :world)")
          tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          underscore-path (.getAbsolutePath underscore-file)
          validated (tool-system/validate-inputs tool-instance {:path underscore-path})
          result (tool-system/execute-tool tool-instance validated)
          formatted (tool-system/format-results tool-instance result)]
      (is (not (:error formatted)) "Should successfully read the file")
      (.delete underscore-file)))

  (testing "Neither dash nor underscore version exists - returns normal error"
    (let [tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          non-existent-path (.getAbsolutePath (io/file *test-dir* "no-such-file.clj"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not exist"
                            (tool-system/validate-inputs tool-instance {:path non-existent-path}))
          "Should throw an error when neither file version exists")))

  (testing "Non-Clojure files (.java) - do NOT auto-correct"
    (let [underscore-file (io/file *test-dir* "core_stuff.java")
          _ (spit underscore-file "public class core_stuff {}")
          tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          dash-path (.getAbsolutePath (io/file *test-dir* "core-stuff.java"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not exist"
                            (tool-system/validate-inputs tool-instance {:path dash-path}))
          "Should NOT auto-correct non-Clojure file extensions")
      (.delete underscore-file)))

  (testing "Directory components with dashes - only filename corrected"
    (let [dashed-dir (io/file *test-dir* "my-cool-dir")
          _ (.mkdirs dashed-dir)
          underscore-file (io/file dashed-dir "my_file.clj")
          _ (spit underscore-file "(ns my-cool-dir.my-file)")
          tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          dash-path (.getAbsolutePath (io/file dashed-dir "my-file.clj"))
          validated (tool-system/validate-inputs tool-instance {:path dash-path})
          result (tool-system/execute-tool tool-instance validated)
          formatted (tool-system/format-results tool-instance result)]
      (is (not (:error formatted)) "Should read file with dashed directory and corrected filename")
      (is (str/includes? (:path validated) "my-cool-dir")
          "Directory dashes should be preserved")
      (.delete underscore-file)
      (.delete dashed-dir)))

  (testing "Full pipeline through make-tool-tester with dash correction"
    (let [underscore-file (io/file *test-dir* "pipeline_test.clj")
          _ (spit underscore-file "(ns pipeline-test)\n(defn greet [name] (str \"Hello \" name))")
          tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          tool-fn (test-utils/make-tool-tester tool-instance)
          dash-path (.getAbsolutePath (io/file *test-dir* "pipeline-test.clj"))
          result (tool-fn {:path dash-path})]
      (is (not (:error? result)) "Full pipeline should succeed with dash correction")
      (.delete underscore-file)))

  (testing "Babashka (.bb) files - do NOT auto-correct"
    (let [underscore-file (io/file *test-dir* "my_script.bb")
          _ (spit underscore-file "(println :hello)")
          tool-instance (unified-read-file-tool/create-unified-read-file-tool *nrepl-client-atom*)
          dash-path (.getAbsolutePath (io/file *test-dir* "my-script.bb"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not exist"
                            (tool-system/validate-inputs tool-instance {:path dash-path}))
          "Should NOT auto-correct .bb files")
      (.delete underscore-file))))

