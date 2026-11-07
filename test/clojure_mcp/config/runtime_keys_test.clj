(ns clojure-mcp.config.runtime-keys-test
  "The project keys that steer the runtime tools: which shadow-cljs build
   cljs_eval targets by default, and how the agent is told to start a REPL."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]
            [clojure-mcp.config.schema :as schema]))

(deftest schema-accepts-runtime-keys
  (testing "a project may name its default build and its REPL start command"
    (is (schema/valid? {:cljs-build :dev :start-repl-hint "bb start:detach"}))
    (is (schema/valid? {:start-repl-hint ["bb start:detach" "clj -M:nrepl"]})))
  (testing "a blank hint or a string build would reach the agent as junk"
    (is (not (schema/valid? {:start-repl-hint ""})))
    (is (not (schema/valid? {:cljs-build "dev"})))))

(deftest start-repl-hints-normalized
  (is (= ["x"] (config/get-start-repl-hints {::config/config {:start-repl-hint "x"}})))
  (is (= ["a" "b"] (config/get-start-repl-hints {::config/config {:start-repl-hint ["a" "b"]}})))
  (is (= [] (config/get-start-repl-hints {::config/config {}}))))

(deftest cljs-build-getter
  (is (= :dev (config/get-cljs-build {::config/config {:cljs-build :dev}})))
  (is (nil? (config/get-cljs-build {::config/config {}}))))
