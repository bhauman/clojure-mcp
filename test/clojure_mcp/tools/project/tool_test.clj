(ns clojure-mcp.tools.project.tool-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure-mcp.nrepl :as nrepl]
   [clojure-mcp.tools.project.tool :as sut]
   [clojure-mcp.tools.project.core :as core]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.config :as config]
   [nrepl.server :as nrepl-server]
   [clojure-mcp.tools.test-utils :as test-utils]))

;; Test fixtures
(def ^:dynamic *nrepl-server* nil)
(def ^:dynamic *client-atom* nil)

(defn setup-nrepl-client
  "Sets up an nREPL client for testing the project inspection tool.
   Using a fixture ensures proper test environment cleanup."
  [f]
  (let [server (nrepl-server/start-server :port 0) ; Use port 0 for dynamic port assignment
        port (:port server)
        client (nrepl/create {:port port})
        ;; Get the working directory
        user-dir (System/getProperty "user.dir")
        ;; Set up config like in real usage
        client-with-config (assoc client
                                  ::config/config
                                  {:nrepl-user-dir user-dir
                                   :allowed-directories [user-dir]})
        client-atom (atom client-with-config)]
    (nrepl/start-polling client-with-config)
    (nrepl/eval-code client-with-config "(require 'clojure.repl)" identity)
    (nrepl/eval-code client-with-config "(require 'clojure.edn)" identity)
    (binding [*nrepl-server* server
              *client-atom* client-atom]
      (try
        (f)
        (finally
          (nrepl/stop-polling client-with-config)
          (nrepl-server/stop-server server))))))

(use-fixtures :once setup-nrepl-client)

;; Helper functions
(defn make-test-tool [tool-map]
  "Creates a test function that wraps the tool function with a synchronous API"
  (fn [inputs]
    (let [prom (promise)
          tool-fn (:tool-fn tool-map)]
      (tool-fn nil inputs
               (fn [result error]
                 (deliver prom {:result result :error error})))
      @prom)))

;; Tests
(deftest inspect-project-tool-test
  (testing "Project inspection tool creation"
    (let [tool-config (sut/create-project-inspection-tool *client-atom*)]
      (is (= :clojure-inspect-project (:tool-type tool-config)))
      (is (= *client-atom* (:nrepl-client-atom tool-config))))))

(deftest tool-registration-test
  (testing "Tool registration map is correctly formed"
    (let [reg-map (sut/inspect-project-tool *client-atom*)]
      (is (= "clojure_inspect_project" (:name reg-map)))
      (is (string? (:description reg-map)))
      (is (map? (:schema reg-map)))
      (is (fn? (:tool-fn reg-map))))))

(deftest multimethod-implementations-test
  (testing "Required multimethods are implemented"
    (let [tool-config (sut/create-project-inspection-tool *client-atom*)]
      (is (= "clojure_inspect_project" (tool-system/tool-name tool-config)))
      (is (string? (tool-system/tool-description tool-config)))
      (is (map? (tool-system/tool-schema tool-config))))))

(deftest format-project-info-test
  (testing "format-project-info function works correctly"
    (let [sample-data {:working-dir "/test/dir"
                       :project-type "deps.edn"
                       :clojure-version "1.11.0"
                       :java-version "17.0.1"
                       :source-paths ["src"]
                       :test-paths ["test"]
                       :namespaces ["test.core"]
                       :sources ["/test/dir/src/test/core.clj"]}
          allowed-dirs ["/test/dir"]
          working-dir "/test/dir"
          formatted (core/format-project-info sample-data allowed-dirs working-dir)]
      (is (string? formatted) "Should return a formatted string")
      (is (.contains formatted "Clojure Project Information") "Should contain project info header")
      (is (.contains formatted "Environment:") "Should contain environment section")
      (is (.contains formatted "Source Paths:") "Should contain source paths section"))))

(deftest integration-test-with-real-repl
  (testing "Project inspection with real REPL connection"
    ;; First directly test the inspect-project function
    (let [direct-result (core/inspect-project @*client-atom*)]
      (is (map? direct-result) "Should return a result map"))

    ;; Now test the full tool pipeline
    (let [tool-config (sut/create-project-inspection-tool *client-atom*)
          result (tool-system/execute-tool tool-config {})
          formatted (tool-system/format-results tool-config result)]

      (is (map? formatted) "Should return a formatted result map")
      (is (contains? formatted :error) "Should have an error field")
      (is (false? (:error formatted)) "Tool execution should not result in an error")
      (is (vector? (:result formatted)) "Result should be a vector")
      (is (= 1 (count (:result formatted))) "Should return a single result string")

      (let [output (first (:result formatted))]
        (is (string? output) "Output should be a string")
        (is (.contains output "Clojure Project Information") "Should contain project info header")
        (is (.contains output "Environment:") "Should contain environment section")
        (is (.contains output "Source Paths:") "Should contain source paths section")
        (is (.contains output "Test Paths:") "Should contain test paths section")))))

(deftest functional-test-with-tool-fn
  (testing "Tool function can be called with callbacks"
    (let [reg-map (sut/inspect-project-tool *client-atom*)
          test-tool (make-test-tool reg-map)
          result (test-tool {})]

      (is (map? result) "Should return a result map")
      (is (contains? result :error) "Should have an error field")
      (is (vector? (:result result)) "Result should be a vector")

      (let [output (first (:result result))]
        (is (string? output) "Output should be a string")
        (is (.contains output "Clojure Project Information") "Should contain project info header")
        (is (.contains output "Environment:") "Should contain environment section")))))

(comment
  ;; For REPL testing
  (def client (nrepl/create {:port 7888}))
  (nrepl/start-polling client)
  (def client-atom (atom client))

  ;; Test tool
  (def tool-config (sut/create-project-inspection-tool client-atom))
  (def result (tool-system/execute-tool tool-config {}))
  (def formatted (tool-system/format-results tool-config result))
  (println (first (:result formatted)))

  ;; Test with direct tool-fn
  (def reg-map (sut/inspect-project-tool client-atom))
  (def test-tool (make-test-tool reg-map))
  (def result (test-tool {}))
  (println (first (:result result)))

  ;; Clean up
  (nrepl/stop-polling client))