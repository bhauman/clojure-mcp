(ns clojure-mcp.tools.deps-read.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.tools.deps-read.core :as sut]
            [clojure.java.io :as io]))

(deftest validate-jar-path-test
  (let [home (System/getProperty "user.home")
        m2-jar (str home "/.m2/repository/org/clojure/clojure/1.11.1/clojure-1.11.1.jar")
        cache-jar (str home "/.clojure-mcp/deps_cache/org/clojure/clojure-1.11.1-sources.jar")]

    (testing "accepts paths under ~/.m2/repository"
      (is (string? (sut/validate-jar-path! m2-jar))))

    (testing "accepts paths under ~/.clojure-mcp/deps_cache"
      (is (string? (sut/validate-jar-path! cache-jar))))

    (testing "rejects paths outside allowed directories"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/validate-jar-path! "/tmp/evil.jar"))))

    (testing "rejects path traversal attacks"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/validate-jar-path!
                    (str home "/.m2/repository/../../etc/foo.jar")))))))

(deftest allowed-jar-dirs-test
  (testing "returns expected directories"
    (let [home (System/getProperty "user.home")
          dirs (sut/allowed-jar-dirs)]
      (is (= 2 (count dirs)))
      (is (some #(.contains % ".m2/repository") dirs))
      (is (some #(.contains % ".clojure-mcp/deps_cache") dirs)))))
