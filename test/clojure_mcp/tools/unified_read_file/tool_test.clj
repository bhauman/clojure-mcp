(ns clojure-mcp.tools.unified-read-file.tool-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.tools.test-utils :as test-utils :refer [*nrepl-client-atom*]]
   [clojure-mcp.tools.unified-read-file.tool :as unified-read-file-tool]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.config :as config]
   [clojure.java.io :as io]))

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
          ;; Clean up
          (when (.exists test-dir)
            (.delete test-dir)))))))

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
