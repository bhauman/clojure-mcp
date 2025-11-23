(ns clojure-mcp.tools.bash.session-test
  "Test for bash tool session functionality"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.tools.bash.tool :as bash-tool]
            [clojure-mcp.tools.eval.core :as eval-core]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.config :as config]
            [clojure-mcp.tool-system :as tool-system]))

(deftest test-bash-execution-uses-session
  (testing "Bash command execution passes session-type to evaluate-code"
    (let [captured-args (atom nil)
          mock-client {:client :mock-client
                       ::nrepl/state (atom {})
                       ::config/config {:allowed-directories [(System/getProperty "user.dir")]
                                        :nrepl-user-dir (System/getProperty "user.dir")
                                        :bash-over-nrepl true}}
          client-atom (atom mock-client)
          bash-tool-config (bash-tool/create-bash-tool client-atom)]

        ;; Mock the evaluate-code function to capture its arguments
        (with-redefs [clojure-mcp.tools.eval.core/evaluate-code
                      (fn [_client opts]
                        (reset! captured-args opts)
                        {:outputs [[:value "{:exit-code 0 :stdout \"test\" :stderr \"\" :timed-out false}"]]
                         :error false})]

          ;; Execute a bash command
          (let [inputs {:command "echo test"
                        :working-directory (System/getProperty "user.dir")
                        :timeout-ms 30000}
                result (tool-system/execute-tool bash-tool-config inputs)]

            ;; Verify the session-type was passed to evaluate-code
            (is (not (nil? @captured-args)))
            (is (contains? @captured-args :session-type))
            (is (= :tools (:session-type @captured-args)))

            ;; Verify the result is properly formatted
            (is (map? result))
            (is (= 0 (:exit-code result)))
            (is (= "test" (:stdout result))))))))
