(ns clojure-mcp.daemon.server-test
  "The daemon is the only thing between a local HTTP port and eval, bash and
   file writes. These drive it over real HTTP: the auth checks, per-project
   routing (no project may reach another's REPL), per-session env, and the state
   files the shim relies on."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure-mcp.daemon.server :as server]
            [nrepl.server :as nrepl-server])
  (:import (java.net Socket URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)))

(def ^:dynamic *daemon* nil)
(def ^:dynamic *nrepl-port* nil)
(def ^:private state-dir (atom nil))

(defn- temp-dir [prefix]
  (.getCanonicalPath (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))))

(use-fixtures :once
  (fn [f]
    (let [nrepl (nrepl-server/start-server :port 0)
          dir (temp-dir "daemon-state")
          daemon (server/start! {:version "test-v" :state-dir dir :env (into {} (System/getenv))
                                 :idle-exit-ms 3600000})]
      (reset! state-dir dir)
      (try
        (binding [*daemon* daemon *nrepl-port* (:port nrepl)] (f))
        (finally ((:stop! daemon)) (nrepl-server/stop-server nrepl))))))

(def ^:private client (HttpClient/newHttpClient))

(defn- url [path] (str "http://127.0.0.1:" (:port *daemon*) path))

(defn- send! [method path {:keys [body headers token?] :or {token? true}}]
  (let [b (cond-> (HttpRequest/newBuilder (URI. (url path)))
            token? (.header "Authorization" (str "Bearer " (:token *daemon*))))
        b (reduce-kv (fn [b k v] (.header b k v)) b (or headers {}))
        b (case method
            :get (.GET b)
            :post (.POST b (HttpRequest$BodyPublishers/ofString (json/write-str body)))
            :delete (.DELETE b))]
    (.send client (.build b) (HttpResponse$BodyHandlers/ofString))))

(defn- mcp-path [dir]
  (str "/p/" (string/join "/" (map #(.replace (java.net.URLEncoder/encode ^String % "UTF-8") "+" "%20")
                                  (rest (string/split dir #"/"))))
       "/mcp"))

(defn- messages
  "JSON-RPC messages in a Streamable HTTP response, JSON or SSE."
  [resp]
  (let [body (.body resp)]
    (if (string/starts-with? (or (.orElse (.firstValue (.headers resp) "content-type") "") "") "text/event-stream")
      (->> (string/split-lines body)
           (keep #(second (re-matches #"data:\s?(.*)" %)))
           (map #(json/read-str % :key-fn keyword)))
      (when-not (string/blank? body) [(json/read-str body :key-fn keyword)]))))

(defn- session [dir & [extra-headers]]
  (let [path (mcp-path dir)
        base (merge {"Accept" "application/json, text/event-stream"
                     "Content-Type" "application/json"}
                    extra-headers)
        init (send! :post path {:headers base
                                :body {:jsonrpc "2.0" :id 1 :method "initialize"
                                       :params {:protocolVersion "2025-06-18" :capabilities {}
                                                :clientInfo {:name "t" :version "1"}}}})
        sid (.orElse (.firstValue (.headers init) "mcp-session-id") nil)
        headers (assoc base "Mcp-Session-Id" sid "MCP-Protocol-Version" "2025-06-18")
        ids (atom 1)]
    (send! :post path {:headers headers :body {:jsonrpc "2.0" :method "notifications/initialized"}})
    (fn [method params]
      (let [id (swap! ids inc)]
        (some #(when (= id (:id %)) %)
              (messages (send! :post path {:headers headers
                                           :body {:jsonrpc "2.0" :id id :method method :params params}})))))))

(defn- call-text [rpc tool args]
  (let [{:keys [result]} (rpc "tools/call" {:name tool :arguments args})]
    {:text (string/join "\n" (map :text (:content result))) :error? (:isError result)}))

(defn- raw-status [host-header]
  (with-open [s (Socket. "127.0.0.1" (int (:port *daemon*)))]
    (let [out (.getOutputStream s)]
      (.write out (.getBytes (str "GET /health HTTP/1.1\r\nHost: " host-header "\r\n"
                                  "Authorization: Bearer " (:token *daemon*) "\r\nConnection: close\r\n\r\n")))
      (.flush out)
      (second (re-find #"HTTP/1.1 (\d+)" (first (line-seq (io/reader (.getInputStream s)))))))))

(deftest auth-and-host-checks
  (is (= 401 (.statusCode (send! :get "/health" {:token? false}))))
  (is (= 401 (.statusCode (send! :get "/health" {:token? false :headers {"Authorization" "Bearer nope"}}))))
  (testing "a browser page cannot reach the daemon even with the token"
    (is (= 403 (.statusCode (send! :get "/health" {:headers {"Origin" "http://evil.example"}})))))
  (testing "DNS rebinding: a foreign Host is refused"
    (is (= "403" (raw-status "evil.example:1")))
    (is (= "200" (raw-status (str "localhost:" (:port *daemon*))))))
  (let [r (send! :get "/health" {})]
    (is (= 200 (.statusCode r)))
    (is (= "test-v" (get (json/read-str (.body r)) "version"))))
  (is (= 404 (.statusCode (send! :get "/nope" {})))))

(deftest mcp-roundtrip-per-project
  (let [a (temp-dir "proj-a")
        b (temp-dir "proj-b")]
    (spit (io/file a ".nrepl-port") (str *nrepl-port*))
    (let [rpc-a (session a)
          names (set (map :name (get-in (rpc-a "tools/list" {}) [:result :tools])))]
      (is (contains? names "clojure_eval"))
      (is (contains? names "cljs_eval"))
      (let [{:keys [text error?]} (call-text rpc-a "clojure_eval" {:code "(+ 1 2)"})]
        (is (false? error?))
        (is (string/starts-with? text "=> 3") text))
      (testing "project B has no REPL of its own and must not reach A's"
        (let [{:keys [text error?]} (call-text (session b) "clojure_eval" {:code "(+ 1 2)"})]
          (is (true? error?))
          (is (string/includes? text "No nREPL")))))))

(deftest path-with-space-routes
  (let [dir (str (temp-dir "proj") "/my proj")]
    (.mkdirs (io/file dir))
    (spit (io/file dir ".nrepl-port") (str *nrepl-port*))
    (is (false? (:error? (call-text (session dir) "clojure_eval" {:code "(+ 1 2)"}))))))

(deftest session-env-reaches-bash
  (let [dir (temp-dir "proj-env")
        env {"PATH" (System/getenv "PATH") "ZZ" "e1"}
        id (get (json/read-str (.body (send! :post "/env" {:body env}))) "id")]
    (is (re-matches #"[0-9a-f]{64}" id))
    (is (string/ends-with? (:text (call-text (session dir {"X-Clojure-Mcp-Env" id}) "bash" {:command "printf %s \"$ZZ\""}))
                           "Standard output:\ne1"))
    (testing "a session that forwarded no env does not see another session's"
      (is (not (string/includes? (:text (call-text (session dir) "bash" {:command "printf %s \"$ZZ\""})) "e1"))))))

(deftest disabled-tool-says-why
  (let [dir (temp-dir "proj-cfg")]
    (io/make-parents (io/file dir ".clojure-mcp" "config.edn"))
    (spit (io/file dir ".clojure-mcp" "config.edn") "{:disable-tools [:bash]}")
    (let [{:keys [text error?]} (call-text (session dir) "bash" {:command "echo hi"})]
      (is (true? error?))
      (is (string/includes? text "config.edn"))))
  (testing "a session's own DISABLE_TOOLS turns the tool off for that session, and the message names it"
    (let [dir (temp-dir "proj-env-filter")
          id (get (json/read-str (.body (send! :post "/env" {:body {"PATH" (System/getenv "PATH") "DISABLE_TOOLS" "bash"}}))) "id")
          {:keys [text error?]} (call-text (session dir {"X-Clojure-Mcp-Env" id}) "bash" {:command "echo hi"})]
      (is (true? error?))
      (is (string/includes? text "DISABLE_TOOLS") text)
      (is (false? (:error? (call-text (session dir) "bash" {:command "echo hi"}))) "other sessions keep it"))))

(deftest state-files-and-modes
  (let [dir (io/file @state-dir)
        perms #(PosixFilePermissions/toString (Files/getPosixFilePermissions (.toPath ^java.io.File %) (make-array LinkOption 0)))]
    (is (= "rwx------" (perms dir)))
    (is (= "rw-------" (perms (io/file dir "token"))))
    (is (= (:token *daemon*) (slurp (io/file dir "token"))))
    (is (= (str (:port *daemon*)) (slurp (io/file dir "port"))))
    (is (= (str (.pid (java.lang.ProcessHandle/current))) (slurp (io/file dir "pid"))))))

(deftest idle-exit
  (let [dir (temp-dir "daemon-idle")
        d (server/start! {:version "idle-v" :state-dir dir :env {} :idle-exit-ms 500})]
    (is (.exists (io/file dir "port")))
    (is (true? (deref (:stopped d) 5000 :timeout)))
    (is (not (.exists (io/file dir "port"))))))

(deftest cljs-eval-uses-the-calling-projects-shadow-port
  (testing "cljs_eval with no port reads .shadow-cljs/nrepl.port of the project in the URL"
    (let [api (create-ns 'shadow.cljs.devtools.api)
          calls (atom [])
          dir (temp-dir "proj-cljs")
          other (temp-dir "proj-other")]
      (intern api 'active-builds (fn [] #{:dev}))
      (intern api 'cljs-eval (fn [bid code _] (swap! calls conj [bid code]) {:results ["\"v-stub\""] :out "" :err ""}))
      (dosync (commute @#'clojure.core/*loaded-libs* conj 'shadow.cljs.devtools.api))
      (try
        (io/make-parents (io/file dir ".shadow-cljs" "nrepl.port"))
        (spit (io/file dir ".shadow-cljs" "nrepl.port") (str *nrepl-port*))
        (let [{:keys [text error?]} (call-text (session dir) "cljs_eval" {:code "js/process.version"})]
          (is (false? error?) text)
          (is (string/includes? text "v-stub"))
          (is (= [[:dev "js/process.version"]] @calls)))
        (testing "a project without a shadow port file is told so, not routed to another project's"
          (let [{:keys [text error?]} (call-text (session other) "cljs_eval" {:code "1"})]
            (is (true? error?))
            (is (string/includes? text ".shadow-cljs/nrepl.port"))
            (is (= 1 (count @calls)))))
        (finally
          (dosync (commute @#'clojure.core/*loaded-libs* disj 'shadow.cljs.devtools.api))
          (remove-ns 'shadow.cljs.devtools.api))))))

(deftest one-daemon-per-state-dir
  (testing "a second daemon of the same version refuses to start instead of overwriting the first one's token"
    (let [dir (temp-dir "daemon-state")
          a (server/start! {:version "v" :state-dir dir :env {} :idle-exit-ms 3600000})]
      (try
        (let [b (try (server/start! {:version "v" :state-dir dir :env {} :idle-exit-ms 3600000})
                     (catch clojure.lang.ExceptionInfo e e))]
          (when (map? b) ((:stop! b)))
          (is (instance? clojure.lang.ExceptionInfo b))
          (is (= (:token a) (slurp (io/file dir "token"))))
          (is (= (str (:port a)) (slurp (io/file dir "port")))))
        (finally ((:stop! a)))))
    (testing "and the dir is free again once the first one stops"
      (let [dir (temp-dir "daemon-state")
            a (server/start! {:version "v" :state-dir dir :env {} :idle-exit-ms 3600000})
            _ ((:stop! a))
            b (server/start! {:version "v" :state-dir dir :env {} :idle-exit-ms 3600000})]
        ((:stop! b))
        (is (some? (:port b)))))))

(deftest starts-whatever-env-the-home-config-refers-to
  (testing "an [:env VAR] ref in the home config the starting shell lacks does not stop the daemon: sessions that have VAR still need it"
    (let [home (temp-dir "home")
          old-home (System/getProperty "user.home")]
      (io/make-parents (io/file home ".clojure-mcp" "config.edn"))
      (spit (io/file home ".clojure-mcp" "config.edn")
            (pr-str {:models {:openai/mine {:model-name "gpt-4o-mini" :api-key [:env "ZZ_NOT_IN_THE_STARTING_SHELL"]}}}))
      (System/setProperty "user.home" home)
      (try
        (let [d (server/start! {:version "v" :state-dir (temp-dir "daemon-state") :env {} :idle-exit-ms 3600000})]
          ((:stop! d))
          (is (some? (:port d))))
        (finally (System/setProperty "user.home" old-home))))))
