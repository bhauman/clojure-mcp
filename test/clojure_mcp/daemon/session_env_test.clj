(ns clojure-mcp.daemon.session-env-test
  "One daemon serves sessions started from different shells. Whatever reads the
   environment on a caller's behalf (a local bash command, the API-key lookup
   for agent tools) must see that caller's environment, not the environment of
   whichever shell happened to spawn the daemon."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.agent.langchain :as chain]
            [clojure-mcp.config :as config]
            [clojure-mcp.config.schema :as schema]
            [clojure-mcp.tools.bash.core :as bash]))

(defn- run [cmd]
  (bash/execute-bash-command nil {:command cmd :timeout-ms 10000}))

(deftest bash-sees-session-env
  (binding [config/*session-env* {"PATH" (System/getenv "PATH") "ZZ_SESSION" "s1"}]
    (is (= "s1" (:stdout (run "echo $ZZ_SESSION")))))
  (testing "a variable of the daemon's own env that the caller does not have is not leaked"
    (binding [config/*session-env* {"PATH" (System/getenv "PATH")}]
      (is (= "" (:stdout (run "echo $HOME")))))))

(deftest bash-without-session-env-inherits
  (is (= (System/getenv "HOME") (:stdout (run "echo $HOME")))))

(deftest agent-model-follows-the-caller
  (binding [config/*session-env* {"ANTHROPIC_API_KEY" "x"}]
    (is (some? (chain/agent-model))))
  (testing "a key only the daemon's env has does not count for a caller without it"
    (binding [config/*session-env* {}]
      (is (nil? (chain/agent-model))))))

(deftest tool-filters-follow-the-caller
  (testing "DISABLE_TOOLS / ENABLE_TOOLS in a session's env apply to that session only"
    (binding [config/*session-env* {"DISABLE_TOOLS" "bash"}]
      (is (false? (config/tool-id-enabled? {} :bash))))
    (binding [config/*session-env* {"ENABLE_TOOLS" "glob_files"}]
      (is (false? (config/tool-id-enabled? {} :bash)))
      (is (true? (config/tool-id-enabled? {} :glob_files))))
    (binding [config/*session-env* {}]
      (is (true? (config/tool-id-enabled? {} :bash))))))

(deftest env-refs-validate-against-the-caller
  (testing "[:env VAR] in a config is checked against the caller's env, not the daemon's"
    (binding [config/*session-env* {"ZZ_CALLER_ONLY" "k"}]
      (is (nil? (schema/explain-config {:models {:openai/mine {:model-name "gpt-4o-mini" :api-key [:env "ZZ_CALLER_ONLY"]}}}))))
    (binding [config/*session-env* {}]
      (is (some? (schema/explain-config {:models {:openai/mine {:model-name "gpt-4o-mini" :api-key [:env "HOME"]}}}))))))
