(ns clojure-mcp.tool-system
  "Core system for defining and registering MCP tools.
   This namespace provides multimethods for implementing tools 
   in a modular, extensible way."
  (:require
   [clojure.string :as string]
   [clojure.walk :as walk]
   [clojure.tools.logging :as log]
   [clojure.data.json :as json]))

;; Core multimethods for tool behavior

(defmulti tool-name
  "Returns the name of the tool as a string. Dispatches on :tool-type."
  :tool-type)

(defmethod tool-name :default [tool-config]
  (-> tool-config
      :tool-type
      name
      (string/replace "-" "_")))

(defmulti tool-description
  "Returns the description of the tool as a string. Dispatches on :tool-type."
  :tool-type)

(defmulti tool-schema
  "Returns the parameter validation schema for the tool. Dispatches on :tool-type."
  :tool-type)

(defmulti validate-inputs
  "Validates inputs against the schema and returns validated/coerced inputs.
   Throws exceptions for invalid inputs.
   Dispatches on :tool-type in the tool-config."
  (fn [tool-config inputs] (:tool-type tool-config)))

(defmulti execute-tool
  "Executes the tool with the validated inputs and returns the result.
   Dispatches on :tool-type in the tool-config."
  (fn [tool-config inputs] (:tool-type tool-config)))

(defmulti format-results
  "Formats the results from tool execution into the expected MCP response format.
   Must return a map with :result (a vector or sequence of strings) and :error (boolean).
   The MCP protocol requires that results are always provided as a sequence of strings,
   never as a single string.
   
   This standardized format is then used by the tool-fn to call the callback with:
   (callback (:result formatted) (:error formatted))
   
   Dispatches on :tool-type in the tool-config."
  (fn [tool-config result] (:tool-type tool-config)))

;; Multimethod to assemble the registration map

(defmulti registration-map
  "Creates the MCP registration map for a tool. 
   Dispatches on :tool-type."
  :tool-type)

;; Function to handle java.util.Map and other collection types before keywordizing
(defn convert-java-collections
  "Converts Java collection types to their Clojure equivalents recursively."
  [x]
  (clojure.walk/prewalk
   (fn [node]
     (cond
       (instance? java.util.Map node) (into {} node)
       (instance? java.util.List node) (into [] node)
       (instance? java.util.Set node) (into #{} node)
       :else node))
   x))

;; Helper function to keywordize map keys while preserving underscores
(defn keywordize-keys-preserve-underscores
  "Recursively transforms string map keys into keywords.
   Unlike clojure.walk/keywordize-keys, this preserves underscores.
   Works with Java collection types by converting them first."
  [m]
  (walk/keywordize-keys (convert-java-collections m)))

;; Default implementation for registration-map
(defmethod registration-map :default [tool-config]
  {:name (tool-name tool-config)
   :description (tool-description tool-config)
   :schema (tool-schema tool-config)
   :tool-fn (fn [_ params callback]
              (try
                (let [keywordized-params (keywordize-keys-preserve-underscores params)
                      validated (validate-inputs tool-config keywordized-params)
                      result (execute-tool tool-config validated)
                      formatted (format-results tool-config result)]
                  (callback (:result formatted) (:error formatted)))
                (catch Exception e
                  (log/error e)
                  ;; On error, create a sequence of error messages
                  (let [error-msg (or (ex-message e) "Unknown error")
                        data (ex-data e)
                        ;; Construct error messages sequence
                        error-msgs (cond-> [error-msg]
                                     ;; Add any error-details from ex-data if available
                                     (and data (:error-details data))
                                     (concat (if (sequential? (:error-details data))
                                               (:error-details data)
                                               [(:error-details data)])))]
                    (callback error-msgs true)))))})

(comment
  ;; === Simple testing for the tool-system ===

  ;; Set up nREPL client for testing
  (require '[clojure-mcp.nrepl :as nrepl])
  (require '[clojure-mcp.tools.eval.tool :as eval-tool])

  (def client-atom (atom (nrepl/create {:port 7888})))
  (nrepl/start-polling @client-atom)

  ;; Create a tool instance 
  (def eval-tool-instance (eval-tool/create-eval-tool client-atom))

  ;; Generate the registration map with our debug println statements
  (def reg-map (registration-map eval-tool-instance))

  ;; Get the tool-fn
  (def tool-fn (:tool-fn reg-map))

  ;; Test it directly with string keys (like it would receive from MCP) 
  (tool-fn nil {"code" "(+ 1 2)"}
           (fn [result error] (println "RESULT:" result "ERROR:" error)))

  ;; See what happens with malformed code
  (tool-fn nil {"code" "(+ 1"}
           (fn [result error] (println "ERROR RESULT:" result "ERROR FLAG:" error)))

  ;; Helper function to make testing easier
  (defn test-eval [code]
    (let [p (promise)]
      (tool-fn nil {"code" code}
               (fn [result error]
                 (deliver p {:result result :error? error})))
      @p))

  (test-eval "(+ 1 2)")
  (test-eval "(println \"hello\")\n(+ 3 4)")
  (test-eval "(/ 1 0)") ;; Should trigger error handling

  ;; Clean up
  (nrepl/stop-polling @client-atom))
