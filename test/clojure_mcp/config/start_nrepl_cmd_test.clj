(ns clojure-mcp.config.start-nrepl-cmd-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure-mcp.config :as config]))

;; Test helper function
(defn get-start-nrepl-cmd
  "Returns the :start-nrepl-cmd configuration value.
   This is a shell command string that will be executed to start an nREPL server.
   Returns nil if not configured."
  [nrepl-client-map]
  (config/get-config nrepl-client-map :start-nrepl-cmd))

(deftest get-start-nrepl-cmd-test
  (testing "returns nil when not configured"
    (let [nrepl-client-map {}]
      (is (nil? (get-start-nrepl-cmd nrepl-client-map)))))

  (testing "returns configured command"
    (let [nrepl-client-map {::config/config {:start-nrepl-cmd "lein repl :start"}}]
      (is (= "lein repl :start" (get-start-nrepl-cmd nrepl-client-map)))))

  (testing "handles various command formats"
    (let [commands ["lein repl :start :port 0"
                    "clojure -M:nrepl"
                    "npx shadow-cljs watch app"
                    "boot repl"]]
      (doseq [cmd commands]
        (let [nrepl-client-map {::config/config {:start-nrepl-cmd cmd}}]
          (is (= cmd (get-start-nrepl-cmd nrepl-client-map))))))))

(deftest process-config-start-nrepl-cmd-test
  (testing "validates start-nrepl-cmd is a string"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid Config: :start-nrepl-cmd value: 123 - must be a string command"
         (config/process-config {:start-nrepl-cmd 123} "/tmp"))))

  (testing "accepts valid string commands"
    (let [result (config/process-config
                  {:start-nrepl-cmd "lein repl :start"}
                  "/tmp")]
      (is (= "lein repl :start" (:start-nrepl-cmd result)))))

  (testing "allows nil start-nrepl-cmd"
    (let [result (config/process-config {} "/tmp")]
      (is (nil? (:start-nrepl-cmd result))))))
