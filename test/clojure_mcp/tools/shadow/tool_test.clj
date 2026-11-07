(ns clojure-mcp.tools.shadow.tool-test
  "The shadow tools against a real nREPL whose JVM carries stub
   shadow.cljs.devtools.api / server.runtime namespaces, so the generated
   forms are evaluated for real without a shadow-cljs dependency."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure-mcp.config :as config]
            [clojure-mcp.nrepl :as nrepl]
            [clojure-mcp.tools.eval.tool :as eval-tool]
            [clojure-mcp.tools.shadow.tool :as shadow-tool]
            [nrepl.server :as nrepl-server])
  (:import (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private stub-state (atom nil))
(def ^:private cljs-eval-calls (atom []))
(def ^:dynamic *port* nil)

(defn- install-shadow-stubs! []
  (let [api (create-ns 'shadow.cljs.devtools.api)
        rt (create-ns 'shadow.cljs.devtools.server.runtime)]
    (intern api 'active-builds (fn [] (:active @stub-state)))
    (intern api 'get-build-ids (fn [] (:build-ids @stub-state)))
    (intern api 'get-worker
            (fn [bid]
              {:state-ref (atom {:autobuild true
                                 :runtimes {7 {:client-id 7 :build-id bid :host :node}}
                                 :build-state {:compiler-env
                                               {:cljs.analyzer/namespaces
                                                {'myapp.kernel {} 'myapp.kernel.util {} 'cljs.core {}}}}})}))
    (intern api 'cljs-eval
            (fn [bid code opts]
              (swap! cljs-eval-calls conj [bid code opts])
              {:results ["ok"] :out "" :err "" :ns 'cljs.user}))
    (intern rt 'instance-ref
            (atom {:build-history
                   {:state-ref (atom {:dev {:status :completed
                                            :warnings [{:msg "undeclared var" :resource-name "myapp/a.cljs" :line 3}]}})}}))
    (dosync (commute @#'clojure.core/*loaded-libs* conj
                     'shadow.cljs.devtools.api 'shadow.cljs.devtools.server.runtime))))

(defn- shadow-fixture [f]
  (install-shadow-stubs!)
  (let [server (nrepl-server/start-server :port 0)]
    (try
      (binding [*port* (:port server)] (f))
      (finally
        (nrepl-server/stop-server server)
        (dosync (commute @#'clojure.core/*loaded-libs* disj
                         'shadow.cljs.devtools.api 'shadow.cljs.devtools.server.runtime))
        (remove-ns 'shadow.cljs.devtools.api)
        (remove-ns 'shadow.cljs.devtools.server.runtime)))))

(use-fixtures :once shadow-fixture)
(use-fixtures :each (fn [f] (reset! stub-state {:active #{:dev} :build-ids [:dev :test :npm]})
                      (reset! cljs-eval-calls [])
                      (f)))

(defn- temp-dir []
  (str (Files/createTempDirectory "shadow-tool-test" (make-array FileAttribute 0))))

(defn- client-atom [config]
  (atom (assoc (nrepl/create nil)
               ::config/config (merge {:nrepl-user-dir (temp-dir)} config))))

(defn- call [tool-name args & [config]]
  (let [tool (some #(when (= tool-name (:name %)) %)
                   (shadow-tool/shadow-tools (client-atom config)))
        p (promise)]
    ((:tool-fn tool) nil args (fn [result error?] (deliver p {:text (string/join "\n" result) :error? error?})))
    (deref p 10000 {:text "TIMEOUT" :error? :timeout})))

(deftest cljs-eval-single-active-build
  (let [{:keys [text error?]} (call "cljs_eval" {"code" "(+ 1 2)" "port" *port*})]
    (is (false? error?) text)
    (is (string/includes? text "ok"))
    (is (= [[:dev "(+ 1 2)" {}]] @cljs-eval-calls))))

(deftest cljs-eval-two-active-builds
  (testing "with several builds and no choice made, the agent is shown them instead of a silent pick"
    (reset! stub-state {:active #{:app :dev}})
    (let [{:keys [text error?]} (call "cljs_eval" {"code" "1" "port" *port*})]
      (is (true? error?))
      (is (string/includes? text ":app"))
      (is (string/includes? text ":dev"))
      (is (empty? @cljs-eval-calls)))))

(deftest cljs-eval-config-build
  (reset! stub-state {:active #{:app :dev}})
  (let [{:keys [error?] :as r} (call "cljs_eval" {"code" "1" "port" *port*} {:cljs-build :dev})]
    (is (false? error?) (:text r))
    (is (= :dev (ffirst @cljs-eval-calls)))))

(deftest cljs-eval-runtime-id
  (call "cljs_eval" {"code" "1" "port" *port* "runtime_id" 7})
  (is (= {:runtime-id 7} (nth (first @cljs-eval-calls) 2))))

(deftest cljs-eval-port-from-shadow-file
  (let [dir (temp-dir)
        f (io/file dir ".shadow-cljs" "nrepl.port")]
    (io/make-parents f)
    (spit f (str *port*))
    (let [{:keys [error?] :as r} (call "cljs_eval" {"code" "1"} {:nrepl-user-dir dir})]
      (is (false? error?) (:text r)))))

(deftest stale-shadow-port-file
  (testing "a port file left by a dead shadow-cljs fails fast and says where to look"
    (let [dead-port (with-open [s (ServerSocket. 0)] (.getLocalPort s))
          dir (temp-dir)
          f (io/file dir ".shadow-cljs" "nrepl.port")]
      (io/make-parents f)
      (spit f (str dead-port))
      (let [start (System/currentTimeMillis)
            {:keys [text error?]} (call "cljs_eval" {"code" "1"} {:nrepl-user-dir dir})]
        (is (< (- (System/currentTimeMillis) start) 3000))
        (is (true? error?))
        (is (string/includes? text (str dead-port)))
        (is (string/includes? text "list_nrepl_ports"))))))

(deftest invalid-build-not-evaluated
  (let [{:keys [text error?]} (call "cljs_eval" {"code" "1" "port" *port* "build" "a) (do :b"})]
    (is (true? error?))
    (is (string/includes? text "Invalid `build`"))
    (is (empty? @cljs-eval-calls))))

(deftest cljs-eval-requires-code
  (is (true? (:error? (call "cljs_eval" {"port" *port*})))))

(deftest build-status-reports-warnings
  (let [{:keys [text error?]} (call "cljs_build_status" {"port" *port* "build" "dev"})]
    (is (false? error?) text)
    (is (string/includes? text "undeclared var"))
    (is (string/includes? text "myapp/a.cljs"))))

(deftest project-state-lists-runtimes
  (let [{:keys [text error?]} (call "cljs_project_state" {"port" *port*})]
    (is (false? error?) text)
    (is (string/includes? text ":runtime-id 7"))
    (is (string/includes? text ":inactive-builds #{:test}"))))

(deftest list-ns-filters-by-match
  (let [{:keys [text error?]} (call "cljs_list_ns" {"port" *port* "match" "^myapp\\.kernel"})]
    (is (false? error?) text)
    (is (string/includes? text "myapp.kernel.util"))
    (is (not (string/includes? text "cljs.core")))))

(deftest eval-description-points-at-cljs-eval
  (is (string/includes? (:description (eval-tool/eval-code (client-atom {}))) "cljs_eval")))

(deftest interpolated-args-must-have-their-declared-type
  (testing "match and runtime_id are printed into an evaluated form, and keywordized map
            keys print raw, so anything but a string / positive integer is refused unevaluated"
    (doseq [bad [{"x (swap! clojure-mcp.tools.shadow.tool-test/cljs-eval-calls conj :pwned) :z" 1}
                 ["x"] 1.5 -1]]
      (reset! cljs-eval-calls [])
      (let [{:keys [text error?]} (call "cljs_eval" {"code" "1" "port" *port* "runtime_id" bad})]
        (is (true? error?) (pr-str bad))
        (is (string/includes? text "runtime_id"))
        (is (empty? @cljs-eval-calls))))
    (doseq [bad [{"x (swap! clojure-mcp.tools.shadow.tool-test/cljs-eval-calls conj :pwned) :z" 1}
                 ["x"] 3]]
      (reset! cljs-eval-calls [])
      (let [{:keys [text error?]} (call "cljs_list_ns" {"port" *port* "match" bad})]
        (is (true? error?) (pr-str bad))
        (is (string/includes? text "match"))
        (is (empty? @cljs-eval-calls))))))
