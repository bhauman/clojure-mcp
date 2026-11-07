(ns clojure-mcp.tools.eval.deadline
  "Wall-clock bound for tools that wait on an nREPL.

   An eval's own timeout covers only the eval. Port initialization (a
   `describe` and init evals) and session setup run before it with no timeout
   at all, so against an nREPL that accepts connections and never answers, a
   tool call -- and the agent behind it -- blocked for hours. The deadline
   abandons such a call and tells the agent the REPL is wedged.

   Only tools that read from an nREPL get one: a deadline can only abandon a
   call, and an abandoned write tool would keep writing after the agent was
   told it failed."
  (:require [taoensso.timbre :as log]))

(def default-grace-ms
  "Added on top of a call's own timeout, for port init and session setup."
  60000)

(defn deadline-ms
  "Wall-clock budget for one call: its eval timeout plus `grace-ms`."
  [timeout-ms grace-ms]
  (+ (long timeout-ms) (long grace-ms)))

(defn- eval-timeout-result
  "The timeout message in the eval tools' result shape."
  [msg]
  {:outputs [[:err msg]]
   :error true
   :context nil})

(defn call-with-deadline
  "Runs `thunk` on its own thread and returns its value, or, when it has not
   returned within `deadline-ms`, cancels it and returns
   `(timeout-result msg)` (eval-tool shape by default). The nREPL client's
   response wait is interruptible, so cancelling frees the thread. An
   exception from `thunk` is rethrown as itself."
  ([tool-name deadline-ms thunk]
   (call-with-deadline tool-name deadline-ms thunk eval-timeout-result))
  ([tool-name deadline-ms thunk timeout-result]
   (let [worker (future (thunk))
         res (try
               (deref worker deadline-ms ::timeout)
               (catch java.util.concurrent.ExecutionException e
                 (throw (or (.getCause e) e))))]
     (if (not= res ::timeout)
       res
       (do
         (future-cancel worker)
         (log/warn tool-name "exceeded its deadline of" deadline-ms "ms")
         (timeout-result
          (str tool-name " did not return within " (quot deadline-ms 1000) "s. "
               "The nREPL it talks to is not answering -- often an earlier eval "
               "wedged that JVM. Do not retry against the same REPL: check that "
               "its JVM is alive (jstack <pid>) and restart it, or report it.")))))))
