(ns clojure-mcp.test-helper
  "Test helper namespace that configures the test environment.
   This namespace is loaded automatically before tests run."
  (:require [clojure-mcp.logging :as logging]))

;; Suppress logging during tests
(logging/configure-test-logging!)

(defn run-tests-with-exec
  "Called when using -X:test (incorrect usage).
   Prints helpful message directing user to use -M:test instead."
  [& _args]
  (println "\n⚠️  Error: The :test alias requires the -M flag, not -X")
  (println "\nPlease run tests using:")
  (println "  clojure -M:test")
  (println "\nThe -M flag is required to suppress logging during test runs.")
  (System/exit 1))
