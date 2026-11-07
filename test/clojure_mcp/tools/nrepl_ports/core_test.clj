(ns clojure-mcp.tools.nrepl-ports.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.tools.nrepl-ports.core :as core])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "nrepl-ports-test" (make-array FileAttribute 0))))

(defn- write-shadow-port! [dir content]
  (let [f (io/file dir ".shadow-cljs" "nrepl.port")]
    (io/make-parents f)
    (spit f content)))

(deftest read-shadow-port-file-test
  (testing "shadow-cljs writes its nREPL port under .shadow-cljs/, not .nrepl-port"
    (let [dir (temp-dir)]
      (write-shadow-port! dir "51234\n")
      (is (= 51234 (nrepl/read-shadow-port-file dir)))))
  (testing "missing or garbage files resolve to no port"
    (is (nil? (nrepl/read-shadow-port-file (temp-dir))))
    (let [dir (temp-dir)]
      (write-shadow-port! dir "not-a-port")
      (is (nil? (nrepl/read-shadow-port-file dir))))))

(deftest discover-includes-shadow-port-file
  (testing "a project's shadow nREPL is listed even when lsof does not see it"
    (let [dir (temp-dir)]
      (spit (io/file dir ".nrepl-port") "1111")
      (write-shadow-port! dir "2222")
      (with-redefs [core/get-listening-jvm-ports (constantly [])
                    core/gather-port-info (fn [_host port source _dir]
                                            {:port port :source source})]
        (is (= #{{:port 1111 :source :nrepl-port-file}
                 {:port 2222 :source :shadow-port-file}}
               (set (core/discover-nrepl-ports (str dir)))))))))
