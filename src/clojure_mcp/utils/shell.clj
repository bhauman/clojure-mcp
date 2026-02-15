(ns clojure-mcp.utils.shell
  "Shell and binary utilities."
  (:require
   [clojure.java.shell :as shell]))

(def binary-available?
  "Check if a binary is available and working on the system by running a probe.

   Usage:
   - (binary-available? \"rg\") ; runs: rg --help
   - (binary-available? \"unzip\" \"-v\")"
  (memoize
   (fn [binary-name & args]
     (try
       (let [probe-args (if (seq args) args ["--help"])
             result (apply shell/sh binary-name probe-args)]
         (zero? (:exit result)))
       (catch Exception _ false)))))
