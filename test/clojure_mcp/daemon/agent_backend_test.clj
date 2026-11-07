(ns clojure-mcp.daemon.agent-backend-test
  "Sub-agent tools are listed only when they have a model to call. The
   daemon's agent-model-fallback hook comes last, after a model the project
   configured and after an API key the caller has: it is for when nobody made
   a choice, so it must never displace one."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.daemon.registry :as registry]
            [clojure-mcp.daemon.server :as server]
            [clojure-mcp.tools.agent-tool-builder.core :as agent-core])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (dev.langchain4j.model.anthropic AnthropicChatModel)
           (dev.langchain4j.model.chat ChatModel)
           (dev.langchain4j.model.openai OpenAiChatModel)
           (java.net InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir [prefix]
  (.getCanonicalPath (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))))

(defn- fake-openai
  "An OpenAI-compatible endpoint that answers every chat completion with
   \"agent says hi\" and records each request."
  []
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        reply (json/write-str {:choices [{:index 0 :message {:role "assistant" :content "agent says hi"}
                                          :finish_reason "stop"}]})]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (swap! seen conj {:path (.getPath (.getRequestURI ex)) :body (slurp (.getRequestBody ex))})
                        (let [bytes (.getBytes ^String reply "UTF-8")]
                          (.sendResponseHeaders ex 200 (count bytes))
                          (with-open [out (.getResponseBody ex)] (.write out bytes))))))
    (.start server)
    {:url (str "http://127.0.0.1:" (.getPort (.getAddress server))) :seen seen :stop #(.stop server 0)}))

(defn- fallback
  "An agent-model-fallback hook offering a model only to an env that names
   FALLBACK_URL. Each project dir it is asked for lands in `dirs`."
  [dirs]
  (fn [env]
    (when-let [url (get env "FALLBACK_URL")]
      (fn [dir]
        (swap! dirs conj dir)
        (-> (OpenAiChatModel/builder) (.baseUrl url) (.apiKey "k") (.modelName "m") (.build))))))

(defn- env-id [daemon env]
  (-> (.send (HttpClient/newHttpClient)
             (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (:port daemon) "/env")))
                 (.header "Authorization" (str "Bearer " (:token daemon)))
                 (.POST (HttpRequest$BodyPublishers/ofString (json/write-str env)))
                 (.build))
             (HttpResponse$BodyHandlers/ofString))
      .body json/read-str (get "id")))

(defn- rpc-session [daemon dir & [env]]
  (let [http (HttpClient/newHttpClient)
        eid (some->> env (env-id daemon))
        path (str "/p" dir "/mcp")
        ids (atom 0)
        sid (atom nil)
        send (fn [body]
               (let [b (cond-> (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (:port daemon) path)))
                                   (.header "Authorization" (str "Bearer " (:token daemon)))
                                   (.header "Accept" "application/json, text/event-stream")
                                   (.header "Content-Type" "application/json"))
                         @sid (.header "Mcp-Session-Id" @sid)
                         eid (.header "X-Clojure-Mcp-Env" eid))
                     r (.send http (.build (.POST b (HttpRequest$BodyPublishers/ofString (json/write-str body))))
                              (HttpResponse$BodyHandlers/ofString))]
                 (when-let [s (.orElse (.firstValue (.headers r) "mcp-session-id") nil)] (reset! sid s))
                 (->> (string/split-lines (.body r))
                      (keep #(or (second (re-matches #"data:\s?(.*)" %)) (when (string/starts-with? % "{") %)))
                      (map #(json/read-str % :key-fn keyword))
                      first)))
        rpc (fn [method params] (send {:jsonrpc "2.0" :id (swap! ids inc) :method method :params params}))]
    (rpc "initialize" {:protocolVersion "2025-06-18" :capabilities {} :clientInfo {:name "t" :version "1"}})
    (send {:jsonrpc "2.0" :method "notifications/initialized"})
    rpc))

(defn- start! [env hook]
  (server/start! {:version "v" :state-dir (temp-dir "state") :env env :idle-exit-ms 3600000
                  :agent-model-fallback hook}))

(defn- tool-names [env hook]
  (let [d (start! env hook)]
    (try (set (map :name (get-in ((rpc-session d (temp-dir "p")) "tools/list" {}) [:result :tools])))
         (finally ((:stop! d))))))

(def ^:private agent-tools ["dispatch_agent" "architect" "code_critique" "clojure_edit_agent"])

(deftest agent-tools-listed-with-fallback
  (is (contains? (tool-names {"FALLBACK_URL" "http://127.0.0.1:1"} (fallback (atom []))) "dispatch_agent")))

(deftest agent-tools-absent-without-backend
  (testing "no hook"
    (let [names (tool-names {} nil)]
      (is (contains? names "clojure_eval"))
      (is (not-any? #(contains? names %) agent-tools))))
  (testing "a hook that has no model for the daemon's env"
    (let [names (tool-names {} (fallback (atom [])))]
      (is (contains? names "clojure_eval"))
      (is (not-any? #(contains? names %) agent-tools)))))

(defn- completions [seen]
  (filter #(string/ends-with? (:path %) "/chat/completions") @seen))

(deftest api-key-beats-fallback
  (testing "each session's agent gets its own model: a key in one session's env never serves another"
    (let [{:keys [url seen stop]} (fake-openai)
          dir (temp-dir "p")
          hook (fallback (atom []))
          client (:nrepl-client-atom (registry/get-or-create! (registry/new-registry) dir))
          agent-model (fn [env]
                        (with-bindings (server/session-bindings env dir hook)
                          (:model (agent-core/get-or-create-agent client {:id :t :name "t" :system-message "s"}))))]
      (try
        (is (instance? AnthropicChatModel (agent-model {"ANTHROPIC_API_KEY" "x" "FALLBACK_URL" url})))
        (testing "without a key, the same project and agent get the fallback model"
          (let [m (agent-model {"FALLBACK_URL" url})]
            (is (instance? OpenAiChatModel m))
            (.chat ^ChatModel m "hi")
            (is (= 1 (count (completions seen))))))
        (testing "a session with the same env reuses its agent"
          (is (identical? (agent-model {"FALLBACK_URL" url}) (agent-model {"FALLBACK_URL" url}))))
        (finally (stop))))))

(deftest keyless-session-gets-fallback-on-a-project-a-keyed-session-opened
  (let [{:keys [url seen stop]} (fake-openai)
        dir (temp-dir "p")
        d (start! {"FALLBACK_URL" url} (fallback (atom [])))]
    (try
      ((rpc-session d dir {"ANTHROPIC_API_KEY" "x" "FALLBACK_URL" url}) "tools/call" {:name "glob_files" :arguments {:pattern "*"}})
      (let [{:keys [result]} ((rpc-session d dir {"FALLBACK_URL" url}) "tools/call" {:name "dispatch_agent" :arguments {:prompt "say hi"}})]
        (is (not (:isError result)) (pr-str result))
        (is (= 1 (count (completions seen)))))
      (finally ((:stop! d)) (stop)))))

(deftest project-model-beats-fallback
  (let [{:keys [url seen stop]} (fake-openai)
        mine (fake-openai)
        dir (temp-dir "p")
        d (start! {"FALLBACK_URL" url} (fallback (atom [])))]
    (io/make-parents (io/file dir ".clojure-mcp" "config.edn"))
    (spit (io/file dir ".clojure-mcp" "config.edn")
          (pr-str {:models {:openai/mine {:provider :openai :model-name "gpt-4o-mini" :api-key "k" :base-url (:url mine)}}
                   :tools-config {:architect {:model :openai/mine}}}))
    (try
      (let [{:keys [result]} ((rpc-session d dir {"FALLBACK_URL" url}) "tools/call" {:name "architect" :arguments {:prompt "plan"}})]
        (is (not (:isError result)) (pr-str result))
        (is (= 1 (count (completions (:seen mine)))) "the project's model answered")
        (is (empty? (completions seen)) "the fallback was not called"))
      (finally ((:stop! d)) (stop) ((:stop mine))))))

(deftest dispatch-agent-reaches-fallback
  (let [{:keys [url seen stop]} (fake-openai)
        dirs (atom [])
        dir (temp-dir "p")
        d (start! {"FALLBACK_URL" url} (fallback dirs))]
    (try
      (let [{:keys [result]} ((rpc-session d dir) "tools/call" {:name "dispatch_agent" :arguments {:prompt "say hi"}})]
        (is (not (:isError result)) (pr-str result))
        (is (string/includes? (string/join (map :text (:content result))) "agent says hi"))
        (is (seq (completions seen)))
        (is (= [dir] @dirs) "the hook is asked for the calling project's dir"))
      (finally ((:stop! d)) (stop)))))
