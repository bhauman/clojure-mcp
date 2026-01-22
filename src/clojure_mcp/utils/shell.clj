(ns clojure-mcp.utils.shell
  "Shell and binary utilities."
  (:require
   [clojure.java.shell :as shell]))

(def binary-available?
  "Check if a binary is available and working on the system.
   Tests actual tool execution with --version rather than just PATH existence.
   Results are memoized per binary."
  (memoize
   (fn [binary-name]
     (try
       (let [result (shell/sh binary-name "--version")]
         (zero? (:exit result)))
       (catch Exception _ false)))))
