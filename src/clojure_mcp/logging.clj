(ns clojure-mcp.logging
  "Centralized logging configuration using Timbre.

   This namespace provides functions to configure Timbre-based logging
   for the Clojure MCP server with support for both development and
   production modes."
  (:require [taoensso.timbre :as timbre]))

(def default-log-file ".clojure-mcp/clojure-mcp.log")

(defn configure-logging!
  "Configure Timbre logging with the given options.

   Options:
   - :log-file       Path to log file (default: 'logs/clojure-mcp.log')
   - :enable-logging? Whether to enable logging (default: true)
   - :log-level      Minimum log level (default: :debug)
                     Valid values: :trace :debug :info :warn :error :fatal :report

   Examples:

   Development mode with full logging:
   (configure-logging! {:log-file \"logs/clojure-mcp.log\"
                        :enable-logging? true
                        :log-level :debug})

   Production mode with logging suppressed:
   (configure-logging! {:enable-logging? false})"
  [{:keys [log-file enable-logging? log-level]
    :or {log-file default-log-file
         enable-logging? false
         log-level :debug}}]
  (timbre/set-config!
   {:appenders (if enable-logging?
                 {:spit (assoc
                         (timbre/spit-appender {:fname log-file})
                         :enabled? enable-logging?
                         :min-level (or log-level :report)
                         :ns-filter (if enable-logging?
                                      {:allow #{"clojure-mcp.*"}}
                                      {:deny #{"*"}}))}
                 {})}))

(defn configure-dev-logging!
  "Configure logging for development mode with debug level.
   Convenience function that enables full debug logging to logs/clojure-mcp.log"
  []
  (configure-logging! {:log-file "logs/clojure-mcp.log"
                       :enable-logging? true
                       :log-level :debug}))

(defn configure-prod-logging!
  "Configure logging for production mode with all logging suppressed.
   Convenience function that disables all logging."
  []
  (configure-logging! {:enable-logging? false}))

(defn configure-test-logging!
  "Configure logging for test mode with all logging suppressed.
   Convenience function that disables all logging during tests."
  []
  (configure-logging! {:enable-logging? false}))

(defn suppress-logging-for-tests!
  "Automatically suppress logging if running in test mode.
   Checks for CLOJURE_MCP_TEST environment variable or
   clojure.mcp.test system property."
  []
  (when (or (System/getenv "CLOJURE_MCP_TEST")
            (System/getProperty "clojure.mcp.test"))
    (configure-test-logging!)))
