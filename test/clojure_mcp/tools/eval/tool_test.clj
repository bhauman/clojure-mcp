(ns clojure-mcp.tools.eval.tool-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.tools.eval.tool :as eval-tool]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.nrepl :as nrepl]
   [nrepl.server :as nrepl-server]
   [clojure.string :as str]))

;; Setup nREPL server for tests
(defonce ^:dynamic *nrepl-server* nil)
(defonce ^:dynamic *nrepl-client-atom* nil)

(defn test-nrepl-fixture [f]
  (let [server (nrepl.server/start-server :port 0)
        port (:port server)
        client (clojure-mcp.nrepl/create {:port port})
        client-atom (atom client)]
    (clojure-mcp.nrepl/eval-code client "(require 'clojure.repl)")
    (binding [*nrepl-server* server
              *nrepl-client-atom* client-atom]
      (try
        (f)
        (finally
          (nrepl.server/stop-server server))))))

(use-fixtures :once test-nrepl-fixture)

;; Helper to directly invoke the tool function from our tool registration map
(defn test-tool-execution [code]
  (let [tool-instance (eval-tool/create-eval-tool *nrepl-client-atom*)
        reg-map (tool-system/registration-map tool-instance)
        tool-fn (:tool-fn reg-map)
        promise-result (promise)]
    (tool-fn nil {"code" code}
             (fn [result error?]
               (deliver promise-result {:result result :error? error?})))
    @promise-result))

;; Helper to test just the individual steps of the pipeline
(defn test-pipeline-steps [code]
  (let [tool-instance (eval-tool/create-eval-tool *nrepl-client-atom*)
        inputs {:code code}
        validated (tool-system/validate-inputs tool-instance inputs)
        execution-result (tool-system/execute-tool tool-instance validated)
        formatted-result (tool-system/format-results tool-instance execution-result)]
    formatted-result))

(deftest tool-registration-test
  (testing "Tool registration produces a proper map"
    (let [tool-instance (eval-tool/create-eval-tool *nrepl-client-atom*)
          reg-map (tool-system/registration-map tool-instance)]
      (is (map? reg-map))
      (is (= "clojure_eval" (:name reg-map)))
      (is (string? (:description reg-map)))
      (is (map? (:schema reg-map)))
      (is (fn? (:tool-fn reg-map))))))

(deftest validate-inputs-test
  (testing "Validate accepts valid input and resolves port"
    (let [tool-instance (eval-tool/create-eval-tool *nrepl-client-atom*)
          result (tool-system/validate-inputs tool-instance {:code "(+ 1 2)"})]
      (is (= "(+ 1 2)" (:code result)))
      (is (pos-int? (:port result)))))

  (testing "Validate rejects missing code parameter"
    (let [tool-instance (eval-tool/create-eval-tool *nrepl-client-atom*)]
      (is (thrown? Exception (tool-system/validate-inputs tool-instance {}))))))

(deftest execute-tool-test
  (testing "Execute returns raw outputs with context"
    (let [tool-instance (eval-tool/create-eval-tool *nrepl-client-atom*)
          result (tool-system/execute-tool tool-instance {:code "(+ 1 2)"})]
      (is (map? result))
      (is (contains? result :outputs))
      (is (contains? result :error))
      (is (contains? result :context))
      (is (false? (:error result)))
      (is (vector? (:outputs result)))
      (is (some #(= [:value "3"] %) (:outputs result)))
      ;; Should have :ns entry
      (is (some #(= :ns (first %)) (:outputs result)))
      ;; Context should have env-type
      (is (= :clj (:env-type (:context result)))))))

(deftest format-results-test
  (testing "Format results properly formats outputs with context"
    (let [tool-instance (eval-tool/create-eval-tool *nrepl-client-atom*)
          outputs [[:value "3"] [:ns "user"]]
          context {:env-type :clj}
          result (tool-system/format-results tool-instance {:outputs outputs :error false :context context})]
      (is (map? result))
      (is (contains? result :result))
      (is (contains? result :error))
      (is (false? (:error result)))
      (is (= ["=> 3\n*======== user | clj ========*"] (:result result))))))

(deftest tool-execution-test
  (testing "Basic evaluation includes divider"
    (let [result (test-tool-execution "(+ 1 2)")]
      (is (false? (:error? result)))
      (is (= 1 (count (:result result))))
      (is (str/includes? (first (:result result)) "=> 3"))
      (is (str/includes? (first (:result result)) "*======== user | clj ========*"))))

  (testing "Evaluation with output includes divider"
    (let [result (test-tool-execution "(println \"hello\")")]
      (is (false? (:error? result)))
      (is (= 1 (count (:result result))))
      (is (str/includes? (first (:result result)) "hello"))
      (is (str/includes? (first (:result result)) "=> nil"))
      (is (str/includes? (first (:result result)) "*======== user | clj ========*"))))

  (testing "Evaluation with error"
    (let [result (test-tool-execution "(throw (Exception. \"test error\"))")]
      (is (true? (:error? result)))
      (is (= 1 (count (:result result))))
      (is (str/includes? (first (:result result)) "test error"))
      (is (str/includes? (first (:result result)) "Execution error"))))

  (testing "Multiple expressions each have their own divider"
    (let [result (test-tool-execution "(println \"first\") (+ 10 20)")]
      (is (false? (:error? result)))
      ;; Now we get 2 groups (one per expression with :ns), not 3 with interposed divider
      (is (= 2 (count (:result result))))
      (is (str/includes? (first (:result result)) "first"))
      (is (str/includes? (first (:result result)) "=> nil"))
      (is (str/includes? (second (:result result)) "=> 30"))))

  (testing "Evaluation without linter warnings (no longer using comprehensive linting)"
    ;; With delimiter-only checking, semantic warnings like unused bindings are not detected
    (let [result (test-tool-execution "(let [unused 1] (+ 2 3))")]
      (is (false? (:error? result)))
      (is (= 1 (count (:result result))))
      (is (str/includes? (first (:result result)) "=> 5"))))

  (testing "Evaluation with runtime error (no pre-evaluation linting)"
    ;; With delimiter-only checking, syntax errors are caught at runtime
    (let [result (test-tool-execution "(def ^:dynamic 1)")]
      (is (true? (:error? result)))
      (is (= 1 (count (:result result))))
      ;; Should have error from evaluation, not from linting
      (is (str/includes? (first (:result result)) "Syntax error")))))

(deftest pipeline-test
  (testing "Complete pipeline execution includes divider"
    (let [result (test-pipeline-steps "(+ 1 2)")]
      (is (map? result))
      (is (= 1 (count (:result result))))
      (is (str/includes? (first (:result result)) "=> 3"))
      (is (str/includes? (first (:result result)) "*======== user | clj ========*"))
      (is (false? (:error result))))))
