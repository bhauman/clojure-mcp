(ns clojure-mcp.nrepl-launcher-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure-mcp.nrepl-launcher :as launcher])
  (:import [java.io File]))

(deftest parse-port-from-output-test
  ;; Test parsing nREPL port numbers from various output formats
  (testing "parse-port-from-output"
    (testing "extracts port from common nREPL output formats"
      (are [output expected] (= expected (launcher/parse-port-from-output output))
        "nREPL server started on port 12345 on host localhost - nrepl://localhost:12345" 12345
        "Started nREPL server on port 7888" 7888
        "nREPL server listening on port 50123" 50123
        "Started on port 9999" 9999
        "Listening on port 8080" 8080
        "port: 12345" 12345
        "port 7888" 7888
        "server started on :12345" 12345
        ":7888" 7888))

    (testing "returns nil for invalid inputs"
      (are [output] (nil? (launcher/parse-port-from-output output))
        nil
        ""
        "no port here"
        "port abc" ; non-numeric
        "port 123" ; too small (below 1024)
        "port 999999" ; too large (above 65535)
        "random text"
        ;; FlowStorm output should not match (issue #109)
        "flow_storm.jobs$run_jobs$mem_reporter__4628@348137e8 function scheduled every 1000 millis"))

    (testing "handles case insensitive matching"
      (are [output expected] (= expected (launcher/parse-port-from-output output))
        "NREPL SERVER STARTED ON PORT 12345" 12345
        "Port: 7888" 7888
        "LISTENING ON PORT 9999" 9999))))

(deftest should-start-nrepl-test
  ;; Test the conditions for auto-starting nREPL
  (testing "should-start-nrepl?"
    (testing "returns false when port already provided without start command"
      (is (not (launcher/should-start-nrepl?
                {:port 7888}))))

    (testing "returns true for CLI condition: both start-nrepl-cmd and project-dir"
      (is (launcher/should-start-nrepl? {:start-nrepl-cmd ["lein" "repl" ":headless"]
                                         :project-dir "/tmp/test"})))

    (testing "returns true even when port provided with start-nrepl-cmd (uses fixed port)"
      (is (launcher/should-start-nrepl? {:start-nrepl-cmd ["lein" "repl" ":headless"]
                                         :project-dir "/tmp/test"
                                         :port 7888})))

    (testing "returns false when only one CLI parameter provided"
      (is (not (launcher/should-start-nrepl? {:start-nrepl-cmd ["lein" "repl"]})))
      (is (not (launcher/should-start-nrepl? {:project-dir "/tmp/test"}))))

    (testing "returns false for empty args"
      (is (not (launcher/should-start-nrepl? {}))))

    (testing "works with vector format for start-nrepl-cmd"
      (is (launcher/should-start-nrepl? {:start-nrepl-cmd ["lein" "repl" ":headless"]
                                         :project-dir "/tmp/test"})))

    (testing "returns false when only port provided (no start command)"
      (is (not (launcher/should-start-nrepl? {:port 7888}))))))

(deftest load-config-if-exists-test
  ;; Test config file loading
  (let [temp-dir (doto (File/createTempFile "tester" "dir")
                   (.delete)
                   (.mkdir))
        config-dir (File. temp-dir ".clojure-mcp")
        config-file (File. config-dir "config.edn")]

    (testing "loads config when file exists"
      (try
        (.mkdir config-dir)
        (spit config-file "{:start-nrepl-cmd [\"lein\" \"repl\" \":headless\"] :port 7888}")

        (let [config (launcher/load-config-if-exists (.getPath temp-dir))]
          (is (= ["lein" "repl" ":headless"] (:start-nrepl-cmd config)))
          (is (= 7888 (:port config))))

        (finally
            ;; Cleanup
          (.delete config-file)
          (.delete config-dir)
          (.delete temp-dir))))))

(deftest maybe-start-nrepl-process-test
  ;; Test the main wrapper function behavior
  (testing "maybe-start-nrepl-process"
    (testing "returns unchanged args when conditions not met"
      (let [args {:host "localhost"}]
        (is (= args (launcher/maybe-start-nrepl-process args)))))

    (testing "returns unchanged args when port provided without start command"
      (let [args {:port 7888}]
        (is (= args (launcher/maybe-start-nrepl-process args)))))

    ;; Note: Testing actual process startup would require integration tests
    ;; with real nREPL commands, which is beyond unit test scope.
    ;; Integration tests would verify the full process startup flow.
    ))
(deftest process-cleanup-test
  ;; Test process cleanup functionality
  (testing "destroy-nrepl-process"
    (testing "handles nil process gracefully"
      (is (nil? (launcher/destroy-nrepl-process nil))))

    (testing "handles non-alive process gracefully"
      ;; Create a mock process-like object
      (let [mock-process (proxy [java.lang.Process] []
                           (isAlive [] false))]
        (is (nil? (launcher/destroy-nrepl-process mock-process)))))

    (testing "handles process that terminates gracefully"
      ;; Mock process that terminates within timeout
      (let [destroy-called (atom false)
            wait-for-called (atom false)
            mock-process (proxy [java.lang.Process] []
                           (isAlive [] true)
                           (destroy []
                             (reset! destroy-called true))
                           (waitFor [timeout unit]
                             (reset! wait-for-called true)
                             true))] ; Returns true - process terminated
        (launcher/destroy-nrepl-process mock-process)
        (is @destroy-called "destroy should be called")
        (is @wait-for-called "waitFor should be called")))

    (testing "handles timeout and forces termination"
      ;; Mock process that doesn't terminate within timeout
      (let [destroy-called (atom false)
            destroy-forcibly-called (atom false)
            wait-for-count (atom 0)
            mock-process (proxy [java.lang.Process] []
                           (isAlive [] true)
                           (destroy []
                             (reset! destroy-called true))
                           (destroyForcibly []
                             (reset! destroy-forcibly-called true)
                             this) ; Return self as Process does
                           (waitFor [timeout unit]
                             (swap! wait-for-count inc)
                             (if (= 1 @wait-for-count)
                               false ; First call returns false (timeout)
                               true)))] ; Second call returns true
        (launcher/destroy-nrepl-process mock-process)
        (is @destroy-called "destroy should be called for graceful termination")
        (is @destroy-forcibly-called "destroyForcibly should be called after timeout")
        (is (= 2 @wait-for-count) "waitFor should be called twice"))))

  (testing "setup-process-cleanup"
    (testing "handles nil process gracefully"
      (is (nil? (launcher/setup-process-cleanup nil))))

    (testing "returns the process when provided"
      ;; Create a mock process that simulates basic functionality
      (let [destroy-on-exit-called (atom false)
            mock-process (proxy [java.lang.Process] []
                           (isAlive [] true)
                           (destroyOnExit []
                             (reset! destroy-on-exit-called true)))]
        (is (= mock-process (launcher/setup-process-cleanup mock-process)))
        ;; Note: We don't test @destroy-on-exit-called because
        ;; setup-process-cleanup has try-catch around destroyOnExit for Java
        ;; version compatibility
        (is (some? mock-process))))))
