(ns clojure-mcp.tools.bash.truncation-test
  "Test for bash tool output truncation functionality"
  (:require [clojure.test :refer :all]
            [clojure-mcp.tools.bash.core :as bash-core]
            [clojure-mcp.nrepl :as nrepl]
            [clojure.string :as str]))

(deftest test-truncate-with-limit
  (testing "truncate-with-limit function"
    ;; Test no truncation needed
    (is (= "short string"
           (#'bash-core/truncate-with-limit "short string" 100)))

    ;; Test truncation
    (let [long-str (apply str (repeat 1000 "a"))
          truncated (#'bash-core/truncate-with-limit long-str 100)]
      (is (= 100 (count truncated)))
      (is (str/ends-with? truncated "... (truncated)"))
      (is (= (subs long-str 0 84) (subs truncated 0 84))))

    ;; Test exact limit (no truncation)
    (let [exact-str (apply str (repeat 100 "b"))]
      (is (= exact-str (#'bash-core/truncate-with-limit exact-str 100))))))

(deftest test-execute-bash-command-truncation
  (testing "execute-bash-command applies truncation to outputs"
    ;; Generate a command that produces long output
    (let [;; Create a string that will exceed truncation limits
          long-content (apply str (repeat 10000 "x"))
          ;; Command that outputs to both stdout and stderr
          command (str "echo '" long-content "' && echo '" long-content "' >&2")
          result (bash-core/execute-bash-command nil {:command command})]

      ;; Check that outputs were truncated
      (is (< (count (:stdout result)) (count long-content)))
      (is (< (count (:stderr result)) (count long-content)))

      ;; Check truncation messages
      (is (str/ends-with? (:stdout result) "... (truncated)"))
      (is (str/ends-with? (:stderr result) "... (truncated)"))

      ;; Check that stderr gets roughly half the space
      (let [total-limit (int (* nrepl/truncation-length 0.85))
            stderr-limit (quot total-limit 2)]
        (is (<= (count (:stderr result)) stderr-limit))
        ;; stdout should get remaining space (at least 500 chars)
        (is (>= (count (:stdout result)) 500))))))

(deftest test-execute-bash-command-short-output
  (testing "execute-bash-command doesn't truncate short outputs"
    (let [command "echo 'Hello stdout' && echo 'Hello stderr' >&2"
          result (bash-core/execute-bash-command nil {:command command})]

      ;; Check outputs are not truncated
      (is (= "Hello stdout" (str/trim (:stdout result))))
      (is (= "Hello stderr" (str/trim (:stderr result))))

      ;; No truncation messages
      (is (not (str/ends-with? (:stdout result) "... (truncated)")))
      (is (not (str/ends-with? (:stderr result) "... (truncated)"))))))

(deftest test-consistent-truncation-behavior
  (testing "Local and nREPL execution have consistent truncation"
    ;; This test would compare outputs from both execution modes
    ;; but would require a running nREPL server, so we just verify
    ;; that the truncation limit calculation is the same
    (let [expected-limit (int (* nrepl/truncation-length 0.85))
          ;; Extract the limit from generate-shell-eval-code
          shell-code (bash-core/generate-shell-eval-code "test" nil 1000)
          ;; Find the nrepl-limit value in the generated code
          limit-match (re-find #"nrepl-limit (\d+)" shell-code)
          generated-limit (when limit-match (Integer/parseInt (second limit-match)))]

      (is (= expected-limit generated-limit)
          "Both execution modes should use the same truncation limit"))))
