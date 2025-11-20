(ns clojure-mcp.tools.test-utils
  "Utility functions for testing the tool-system based tools."
  (:require
   [clojure-mcp.nrepl :as nrepl]
   [nrepl.server :as nrepl-server]
   [clojure-mcp.tool-system :as tool-system]
   [clojure.test :refer [use-fixtures]]
   [clojure.java.io :as io]
   [clojure-mcp.tools.unified-read-file.file-timestamps :as file-timestamps]))

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

;; Helper to invoke full tool function directly using the tool registration map
(defn make-tool-tester
  "Takes a tool instance and returns a function that executes the tool directly.
   The returned function takes a map of tool inputs and returns a map with:
   {:result result :error? error-flag}"
  [tool-instance]
  (let [reg-map (tool-system/registration-map tool-instance)
        tool-fn (:tool-fn reg-map)]
    (fn [inputs]
      (let [prom (promise)]
        (tool-fn nil inputs
                 (fn [res error?]
                   (deliver prom {:result res :error? error?})))
        @prom))))

;; Helper to test individual multimethod pipeline steps
(defn test-pipeline-steps
  "Executes the validation, execution, and formatting steps of the tool pipeline
   and returns the formatted result."
  [tool-instance inputs]
  (let [validated (tool-system/validate-inputs tool-instance inputs)
        execution-result (tool-system/execute-tool tool-instance validated)
        formatted-result (tool-system/format-results tool-instance execution-result)]
    formatted-result))

;; Apply fixtures in each test namespace
(defn create-test-dir
  "Creates a temporary test directory with a unique name"
  []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir"))
        test-dir (io/file temp-dir (str "clojure-mcp-test-" (System/currentTimeMillis)))]
    (.mkdirs test-dir)
    (.getAbsolutePath test-dir)))

(defn create-and-register-test-file
  "Creates a test file with the given content and registers it in the timestamp tracker"
  [client-atom dir filename content]
  (let [file-path (str dir "/" filename)
        _ (io/make-parents file-path)
        _ (spit file-path content)
        file-obj (io/file file-path)
        canonical-path (.getCanonicalPath file-obj)]
    ;; Register the file using its canonical path in the timestamp tracker
    (file-timestamps/update-file-timestamp-to-current-mtime! client-atom canonical-path)
    ;; Small delay to ensure future modifications have different timestamps
    (Thread/sleep 25)
    ;; Return the canonical path for consistent usage
    canonical-path))

(defn modify-test-file
  "Modifies a test file and updates its timestamp in the tracker if update-timestamp? is true"
  [client-atom file-path content & {:keys [update-timestamp?] :or {update-timestamp? false}}]
  (spit file-path content)
  (when update-timestamp?
    (file-timestamps/update-file-timestamp-to-current-mtime! client-atom file-path)
    ;; Small delay
    (Thread/sleep 25))
  file-path)

(defn read-and-register-test-file
  "Updates the timestamp for an existing file to mark it as read.
   Normalizes the file path to ensure consistent lookup."
  [client-atom file-path]
  (let [normalized-path (.getCanonicalPath (io/file file-path))]
    (file-timestamps/update-file-timestamp-to-current-mtime! client-atom normalized-path)
    ;; Small delay to ensure timestamps differ if modified
    (Thread/sleep 25)
    normalized-path))

(defn clean-test-dir
  "Recursively deletes a test directory"
  [dir-path]
  (let [dir (io/file dir-path)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))))

(defn apply-fixtures [_test-namespace]
  (use-fixtures :once test-nrepl-fixture)
  (use-fixtures :each cleanup-test-file))
