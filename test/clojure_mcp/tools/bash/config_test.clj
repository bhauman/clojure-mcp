(ns clojure-mcp.tools.bash.config-test
  "Test for bash tool config parameter functionality"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.tools.bash.tool :as bash-tool]
            [clojure-mcp.tools.bash.core :as bash-core]
            [clojure-mcp.config :as config]
            [clojure-mcp.tool-system :as tool-system]))

(deftest test-bash-over-nrepl-config-default
  (testing "bash-over-nrepl defaults to false"
    (let [mock-client {:client :mock-client
                       ::config/config {:allowed-directories [(System/getProperty "user.dir")]
                                        :nrepl-user-dir (System/getProperty "user.dir")}}]
      (is (= false (config/get-bash-over-nrepl mock-client))))))

(deftest test-bash-over-nrepl-config-explicit
  (testing "bash-over-nrepl can be explicitly set"
    (let [mock-client-true {:client :mock-client
                            ::config/config {:allowed-directories ["/tmp"]
                                             :nrepl-user-dir "/tmp"
                                             :bash-over-nrepl true}}
          mock-client-false {:client :mock-client
                             ::config/config {:allowed-directories ["/tmp"]
                                              :nrepl-user-dir "/tmp"
                                              :bash-over-nrepl false}}]
      (is (= true (config/get-bash-over-nrepl mock-client-true)))
      (is (= false (config/get-bash-over-nrepl mock-client-false))))))

(deftest test-bash-execution-respects-config
  (testing "Bash tool uses nREPL or local execution based on config"
    (let [nrepl-calls (atom 0)
          local-calls (atom 0)

          ;; Mock client with bash-over-nrepl = true
          mock-client-nrepl {:client :mock-client
                             ::config/config {:allowed-directories [(System/getProperty "user.dir")]
                                              :nrepl-user-dir (System/getProperty "user.dir")
                                              :bash-over-nrepl true}}
          client-atom-nrepl (atom mock-client-nrepl)

          ;; Mock client with bash-over-nrepl = false
          mock-client-local {:client :mock-client
                             ::config/config {:allowed-directories [(System/getProperty "user.dir")]
                                              :nrepl-user-dir (System/getProperty "user.dir")
                                              :bash-over-nrepl false}}
          client-atom-local (atom mock-client-local)]

      ;; Mock the execution functions
      (with-redefs [bash-core/execute-bash-command-nrepl
                    (fn [_ _]
                      (swap! nrepl-calls inc)
                      {:exit-code 0 :stdout "nrepl" :stderr "" :timed-out false})

                    bash-core/execute-bash-command
                    (fn [_ _]
                      (swap! local-calls inc)
                      {:exit-code 0 :stdout "local" :stderr "" :timed-out false})]

        ;; Create tools
        (let [tool-nrepl (bash-tool/create-bash-tool client-atom-nrepl)
              tool-local (bash-tool/create-bash-tool client-atom-local)
              inputs {:command "echo test"
                      :working-directory (System/getProperty "user.dir")}]

          ;; Execute with nREPL config
          (let [result-nrepl (tool-system/execute-tool tool-nrepl inputs)]
            (is (= 1 @nrepl-calls))
            (is (= 0 @local-calls))
            (is (= "nrepl" (:stdout result-nrepl))))

          ;; Reset counters
          (reset! nrepl-calls 0)
          (reset! local-calls 0)

          ;; Execute with local config
          (let [result-local (tool-system/execute-tool tool-local inputs)]
            (is (= 0 @nrepl-calls))
            (is (= 1 @local-calls))
            (is (= "local" (:stdout result-local)))))))))

(deftest test-process-remote-config-bash-over-nrepl
  (testing "process-remote-config handles bash-over-nrepl parameter"
    (let [config-true {:bash-over-nrepl true
                       :allowed-directories []}
          config-false {:bash-over-nrepl false
                        :allowed-directories []}
          config-nil {:allowed-directories []}
          user-dir (System/getProperty "user.dir")]

      ;; Test with true value
      (let [processed (config/process-config config-true user-dir)]
        (is (= true (:bash-over-nrepl processed))))

      ;; Test with false value
      (let [processed (config/process-config config-false user-dir)]
        (is (= false (:bash-over-nrepl processed))))

      ;; Test with nil/missing value - should not be in result
      (let [processed (config/process-config config-nil user-dir)]
        (is (not (contains? processed :bash-over-nrepl)))))))
