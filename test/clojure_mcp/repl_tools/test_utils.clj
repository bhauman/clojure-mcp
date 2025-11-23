(ns clojure-mcp.repl-tools.test-utils
  (:require
   [clojure-mcp.nrepl :as nrepl]
   [nrepl.server :as nrepl-server]
   [clojure.test :refer [use-fixtures]]))

(defonce ^:dynamic *nrepl-server* nil)
(defonce ^:dynamic *nrepl-client-atom* nil)
(def ^:dynamic *test-file-path* "test_function_edit.clj")

(defn test-nrepl-fixture [f]
  (let [server (nrepl-server/start-server :port 0) ; Use port 0 for dynamic port assignment
        port (:port server)
        client (nrepl/create {:port port})
        client-atom (atom client)]
    (nrepl/eval-code client "(require 'clojure.repl)")
    (binding [*nrepl-server* server
              *nrepl-client-atom* client-atom]
      (try
        (f)
        (finally
          (nrepl-server/stop-server server))))))

(defn cleanup-test-file [f]
  (try
    (f)
    (finally
      #_(io/delete-file *test-file-path* true))))

;; Helper to invoke tool functions more easily in tests
(defn make-test-tool [{:keys [tool-fn] :as _tool-map}]
  (fn [arg-map]
    (let [prom (promise)]
      (tool-fn nil arg-map
               (fn [res error?]
                 (deliver prom {:res res :error? error?})))
      @prom)))

;; Apply fixtures in each test namespace
(defn apply-fixtures [_test-namespace]
  (use-fixtures :once test-nrepl-fixture)
  (use-fixtures :each cleanup-test-file))
