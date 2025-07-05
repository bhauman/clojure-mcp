(ns clojure-mcp.tools.bash.session-test
  "Test for bash tool session functionality"
  (:require [clojure.test :refer :all]
            [clojure-mcp.tools.bash.tool :as bash-tool]
            [clojure-mcp.tools.bash.core :as bash-core]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.config :as config]
            [clojure-mcp.tool-system :as tool-system]))

(deftest test-bash-tool-creates-separate-session
  (testing "Bash tool creates its own nREPL session"
    ;; Create a mock nREPL client
    (let [mock-sessions (atom [])
          mock-client {:client :mock-client
                       ::nrepl/state (atom {})
                       ::config/config {:allowed-directories ["/tmp"]
                                        :nrepl-user-dir "/tmp"}}
          client-atom (atom mock-client)]

      ;; Mock the new-session function to track session creation
      (with-redefs [nrepl/new-session (fn [client]
                                        (let [session-id (str "session-" (count @mock-sessions))]
                                          (swap! mock-sessions conj session-id)
                                          session-id))]
        ;; Create the bash tool
        (let [bash-tool-config (bash-tool/create-bash-tool client-atom)]

          ;; Verify the tool has a session
          (is (contains? bash-tool-config :nrepl-session))
          (is (= "session-0" (:nrepl-session bash-tool-config)))
          (is (= 1 (count @mock-sessions)))

          ;; Create another bash tool to verify it gets a different session
          (let [bash-tool-config-2 (bash-tool/create-bash-tool client-atom)]
            (is (= "session-1" (:nrepl-session bash-tool-config-2)))
            (is (= 2 (count @mock-sessions)))))))))

(deftest test-bash-execution-uses-session
  (testing "Bash command execution passes session to evaluate-code"
    (let [captured-args (atom nil)
          mock-client {:client :mock-client
                       ::nrepl/state (atom {})
                       ::config/config {:allowed-directories [(System/getProperty "user.dir")]
                                        :nrepl-user-dir (System/getProperty "user.dir")}}
          client-atom (atom mock-client)

          ;; Create a bash tool with a mock session
          bash-tool-config (with-redefs [nrepl/new-session (fn [_] "test-session-123")]
                             (bash-tool/create-bash-tool client-atom))]

      ;; Mock the evaluate-code function to capture its arguments
      (with-redefs [clojure-mcp.tools.eval.core/evaluate-code
                    (fn [client opts]
                      (reset! captured-args opts)
                      {:outputs [[:value "{:exit-code 0 :stdout \"test\" :stderr \"\" :timed-out false}"]]
                       :error false})]

        ;; Execute a bash command
        (let [inputs {:command "echo test"
                      :working-directory (System/getProperty "user.dir")}
              result (tool-system/execute-tool bash-tool-config inputs)]

          ;; Verify the session was passed to evaluate-code
          (is (not (nil? @captured-args)))
          (is (contains? @captured-args :session))
          (is (= "test-session-123" (:session @captured-args)))

          ;; Verify the result is properly formatted
          (is (map? result))
          (is (= 0 (:exit-code result)))
          (is (= "test" (:stdout result))))))))

(deftest test-bash-tool-handles-missing-client
  (testing "Bash tool gracefully handles when session cannot be created"
    (let [client-atom (atom nil)
          bash-tool-config (bash-tool/create-bash-tool client-atom)]

      ;; Should still create a tool config, but with nil session
      (is (contains? bash-tool-config :nrepl-session))
      (is (nil? (:nrepl-session bash-tool-config))))))
