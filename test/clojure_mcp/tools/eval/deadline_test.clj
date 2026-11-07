(ns clojure-mcp.tools.eval.deadline-test
  "An nREPL that accepts a connection and never answers used to block the
   agent's tool call for hours (port init and session setup have no timeout
   of their own). The deadline turns that into an error the agent can act on."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.tool-system :as tool-system]
            [clojure-mcp.tools.eval.deadline :as deadline]
            [clojure-mcp.tools.eval.tool :as eval-tool])
  (:import (java.net ServerSocket)))

(defn- err-text [result]
  (string/join "\n" (map second (:outputs result))))

(deftest deadline-passes-value-through
  (is (= {:ok 1} (deadline/call-with-deadline "clojure_eval" 1000 (fn [] {:ok 1})))))

(deftest deadline-expires-with-guidance
  (let [start (System/currentTimeMillis)
        result (deadline/call-with-deadline "clojure_eval" 200 (fn [] (Thread/sleep 5000) :late))
        elapsed (- (System/currentTimeMillis) start)]
    (is (< elapsed 1000))
    (is (true? (:error result)))
    (is (string/includes? (err-text result) "did not return within"))
    (is (string/includes? (err-text result) "Do not retry against the same REPL"))))

(deftest deadline-ms-adds-grace
  (is (= 60200 (deadline/deadline-ms 200 60000))))

(deftest wedged-nrepl-does-not-block
  (testing "a socket that accepts and never replies fails within timeout + grace"
    (with-open [server (ServerSocket. 0)]
      (let [accepted (future (try (.accept server) (catch Exception _ nil)))
            client-atom (atom (assoc (nrepl/create nil)
                                     ::config/config {:nrepl-user-dir (System/getProperty "user.dir")}))
            tool (eval-tool/create-eval-tool client-atom {:deadline-grace-ms 300})
            tool-fn (:tool-fn (tool-system/registration-map tool))
            p (promise)]
        (future (tool-fn nil {"code" "1" "port" (.getLocalPort server) "timeout_ms" 200}
                         (fn [result error?] (deliver p {:result result :error? error?}))))
        (let [{:keys [result error?] :as r} (deref p 3000 ::blocked)]
          (is (not= ::blocked r))
          (is (true? error?))
          (is (string/includes? (string/join "\n" result) "did not return within")))
        (future-cancel accepted)))))

(deftest deadline-custom-timeout-shape
  (testing "tools whose results are plain strings get the message in their own shape"
    (let [result (deadline/call-with-deadline "clojure_inspect_project" 100
                                              (fn [] (Thread/sleep 2000))
                                              (fn [msg] {:outputs [msg] :error true}))]
      (is (true? (:error result)))
      (is (string? (first (:outputs result))))
      (is (string/includes? (first (:outputs result)) "clojure_inspect_project did not return within")))))
