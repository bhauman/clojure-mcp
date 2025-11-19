(ns clojure-mcp.tools.scratch-pad.tool
  "Implementation of the scratch-pad tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.scratch-pad.core :as core]
   [clojure-mcp.utils.file :as file-utils]
   [taoensso.timbre :as log]
   [clojure.walk :as walk]
   [clojure.pprint :as pprint]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure-mcp.config :as config]))

(defn get-scratch-pad
  "Gets the current scratch pad data from the nrepl-client.
   Returns an empty map if no data exists yet."
  [nrepl-client-atom]
  (get @nrepl-client-atom ::scratch-pad {}))

(defn update-scratch-pad!
  "Updates the scratch pad data in the nrepl-client-atom."
  [nrepl-client-atom f & args]
  (apply swap! nrepl-client-atom update ::scratch-pad f args))

(defn scratch-pad-file-path
  "Returns the path to the scratch pad persistence file"
  [working-directory filename]
  (io/file working-directory ".clojure-mcp" filename))

(defn save-scratch-pad!
  "Saves the scratch pad data to disk"
  [working-directory filename data nrepl-client-atom]
  (try
    (let [file (scratch-pad-file-path working-directory filename)
          dir (.getParentFile file)]
      (when-not (.exists dir)
        (.mkdirs dir))
      (file-utils/spit-utf8 file (pr-str data))
      (log/debug "Saved scratch pad to" (.getPath file)))
    (catch Exception e
      (log/error e "Failed to save scratch pad")
      (when nrepl-client-atom
        (remove-watch nrepl-client-atom ::scratch-pad-persistence)))))

(defn load-scratch-pad
  "Loads the scratch pad data from disk if it exists. Returns [data error?]"
  [working-directory filename]
  (try
    (let [file (scratch-pad-file-path working-directory filename)]
      (if (.exists file)
        (let [data (edn/read-string (file-utils/slurp-utf8 file))]
          (log/debug "Loaded scratch pad from" (.getPath file))
          data)
        (do
          (log/debug "No existing scratch pad file found")
          {})))
    (catch Exception e
      (log/error e "Failed to load scratch pad")
      {})))

(defn setup-persistence-watch!
  "Sets up a watch on the atom to save scratch pad changes to disk"
  [nrepl-client-atom working-directory filename]
  (add-watch nrepl-client-atom ::scratch-pad-persistence
             (fn [_key _ref old-state new-state]
               (let [old-data (::scratch-pad old-state)
                     new-data (::scratch-pad new-state)]
                 (when (not= old-data new-data)
                   (save-scratch-pad! working-directory filename new-data nrepl-client-atom))))))

(defmethod tool-system/tool-name :scratch-pad [_]
  "scratch_pad")

(defmethod tool-system/tool-description :scratch-pad [_]
  "A persistent scratch pad for storing structured data between tool calls. Accepts any JSON value (objects, arrays, strings, numbers, booleans, null) and stores them at nested paths using set_path, get_path, delete_path operations.

THIS IS YOUR GO-TO TOOL FOR PLANNING.

Your persistent workspace for planning, organizing thoughts, and maintaining state across tool invocations.

This tool can be used to:
 * develop and track multi-step plans
 * maintain task lists and task tracking
 * store intermediate results between operations
 * keep notes about your current approach or strategy
 * maintain a list of files or resources to process
 * build up complex data structures incrementally
 * share context between different tool calls
 * any other persistent storage needs during your work

The scratch pad is for persistent storage and state management, not for formatting output. Display information directly in your chat responses. However, when the scratch pad contains user-relevant state (like tasks lists or tracked progress), you should retrieve and display updates after modifications.

Use the scratch pad when you need to:
- Track progress across multiple tool calls
- Build data structures incrementally  
- Maintain state between operations
- Store intermediate results for later use

Don't use the scratch pad to:
- Format text for display
- Store static information just to retrieve and show it
- Replace direct chat responses

CORE OPERATIONS:
- set_path: Store a value (not null) at a path, returns the parent container
- get_path: Retrieve a value from a path, returns the value or nil
- delete_path: Remove only the leaf value from a path, returns a confirmation message
- inspect: Display the entire structure (or a specific path) with truncation at specified depth

WARNING: null values can not be stored in the scratch pad, if you attempt to store a null value the set_path will fail.

TRACKING PLANS WITH TASK LISTS:

Recommended schema for a Task:
{
  task: \"Description of the task\",
  done: false,
  priority: \"high\", // optional: \"high\", \"medium\", \"low\"
  context: \"Additional details\", // optional
  subtasks: [  // optional: array of subtask objects
    {task: \"Subtask 1\", done: false},
    {task: \"Subtask 2\", done: true}
  ]
}

First add multiple tasks items at once as an array:
- Entire array:
  op: set_path
  path: [\"tasks\"]
  value: [
    {task: \"Write tests\", done: false, priority: \"high\"},
    {task: \"Review PR\", done: false, priority: \"high\"},
    {task: \"Update docs\", done: false, priority: \"medium\", subtasks: [
      {task: \"Update API docs\", done: false},
      {task: \"Update README\", done: false}
    ]}
  ]
  explanation: Adding multiple tasks at once

Adding tasks items:
- First item:
  op: set_path
  path: [\"tasks\", 3]
  value: {task: \"Run tests\", done: false}
  explanation: Adding write tests task

- Next item:
  op: set_path
  path: [\"tasks\", 4]
  value: {task: \"Notify user\", done: false, priority: \"high\"}
  explanation: Adding high priority task

Checking off completed tasks:
- Mark as done:
  op: set_path
  path: [\"tasks\", 0, \"done\"]
  value: true
  explanation: Completed writing tests

- Mark subtask as done:
  op: set_path
  path: [\"tasks\", 2, \"subtasks\", 0, \"done\"]
  value: true
  explanation: Completed API docs update

Adding a new task to the array:
- Append to array:
  op: set_path
  path: [\"tasks\", 3]
  value: {task: \"Deploy to production\", done: false, priority: \"high\"}
  explanation: Adding deployment task

Viewing tasks:
- All tasks:
  op: get_path
  path: [\"tasks\"]
  explanation: Get all tasks

- Specific task:
  op: get_path
  path: [\"tasks\", 0]
  explanation: Checking first task details

- View with depth limit:
  op: inspect
  path: [\"tasks\"]
  depth: 2
  explanation: View tasks with limited nesting")

(defmethod tool-system/tool-schema :scratch-pad [{:keys [nrepl-client-atom]}]
  (let [client-hint (when nrepl-client-atom
                      (config/get-mcp-client-hint @nrepl-client-atom))
        claude-desktop? (= client-hint :claude-desktop)]
    {:type "object"
     :properties {"op" {:type "string"
                        :enum ["set_path" "get_path" "delete_path" "inspect"]
                        :description "The operation to perform:\n * set_path: set a value at a path\n * get_path: retrieve a value at a path\n * delete_path: remove the value at the path from the data structure\n * inspect: view the datastructure (or a specific path within it) up to a certain depth\n"}
                  "path" {:type "array"
                          :items (if claude-desktop?
                                   {:type ["string" "number"]}
                                   {:type "string"})
                          :description "Path to the data location (array of string or number keys) - used for set_path, get_path, delete_path, and optionally inspect"}
                  "value" {:description "Value to store (for set_path). Can be ANY JSON value EXCEPT null: object, array, string, number, boolean."
                           :type (if claude-desktop?
                                   ["object" "array" "string" "number" "boolean"]
                                   "object")}
                  "explanation" {:type "string"
                                 :description "Explanation of why this operation is being performed"}
                  "depth" {:type "number"
                           :description "(Optional) For inspect operation: Maximum depth to display (default: 5). Must be a positive integer."}}
     :required ["op" "explanation"]}))

(defmethod tool-system/validate-inputs :scratch-pad [{:keys [_nrepl-client-atom]} inputs]
  ;; convert set_path path nil -> delete_path path
  ;; this can prevent nil values from being in the data?
  (let [inputs (if (and
                    (contains? inputs :value)
                    (nil? (:value inputs))
                    (= (:op inputs) "set_path")
                    (:path inputs))
                 (assoc inputs :op "delete_path")
                 inputs)
        {:keys [op path _value explanation depth _enabled _filename]} inputs]

    ;; Check required parameters
    (when-not op
      (throw (ex-info "Missing required parameter: op" {:inputs inputs})))

    (when-not explanation
      (throw (ex-info "Missing required parameter: explanation" {:inputs inputs})))

    ;; Validate operation
    (when-not (#{"set_path" "get_path" "delete_path" "inspect"} op)
      (throw (ex-info "Invalid operation. Must be one of: set_path, get_path, delete_path, inspect"
                      {:op op :inputs inputs})))

    ;; Operation-specific validation
    (case op
      "set_path" (do
                   (when-not path
                     (throw (ex-info "Missing required parameter for set_path: path" {:inputs inputs})))
                   (when-not (contains? inputs :value)
                     (throw (ex-info "Missing required parameter for set_path: value" {:inputs inputs}))))
      ("get_path" "delete_path") (when-not path
                                   (throw (ex-info (str "Missing required parameter for " op ": path")
                                                   {:inputs inputs})))
      "inspect" (when depth
                  (when-not (and (number? depth) (integer? depth) (pos? depth))
                    (throw (ex-info "Depth must be a positive integer greater than 0"
                                    {:depth depth :inputs inputs}))))) ;; no validation needed for status

    ;; Validate path has at least one element when provided
    (when (and path (empty? path) (not= op "inspect"))
      (throw (ex-info "Path must have at least one element" {:path path :inputs inputs})))

    ;; Validate path elements are strings or numbers
    (when path
      (doseq [element path]
        (when-not (or (string? element) (number? element))
          (throw (ex-info "Path elements must be strings or numbers"
                          {:element element :type (type element) :path path})))))

    ;; Convert path to vector if needed (MCP provides as array)
    ;; And ensure depth is provided with default value for inspect
    (cond-> inputs
      path (assoc :path (vec path))
      (and (= op "inspect") (nil? depth)) (assoc :depth 5))))

(defmethod tool-system/execute-tool :scratch-pad [{:keys [nrepl-client-atom _working-directory]} {:keys [op path value explanation depth _enabled _filename]}]
  (try
    (let [current-data (get-scratch-pad nrepl-client-atom)
          exec-result (case op
                        "set_path" (let [{:keys [data result]} (core/execute-set-path current-data path value)]
                                     (update-scratch-pad! nrepl-client-atom (constantly data))
                                     ;; Add parent value to result
                                     (let [parent-path (butlast path)
                                           parent-value (if (empty? parent-path)
                                                          data
                                                          (get-in data parent-path))]
                                       (assoc result :parent-value parent-value)))

                        "get_path" (:result (core/execute-get-path current-data path))

                        "delete_path" (let [{:keys [data result]} (core/execute-delete-path current-data path)]
                                        (update-scratch-pad! nrepl-client-atom (constantly data))
                                        result)

                        "inspect" (:result (core/execute-inspect current-data depth path)))]
      {:result exec-result
       :explanation explanation
       :error false})
    (catch Exception e
      (log/error e (str "Error executing scratch pad operation: " (.getMessage e)))
      {:error true
       :result (str "Error executing scratch pad operation: " (.getMessage e))})))

;; this is convoluted this can be stream lined as most of this is imply echoing back what was sent
(defmethod tool-system/format-results :scratch-pad [_ {:keys [error _message result _explanation]}]
  (if error
    {:result [result]
     :error true}
    (try
      (cond
        ;; set_path - return pprinted parent value
        (:stored-at result)
        {:result [(try
                    (with-out-str (clojure.pprint/pprint (:parent-value result)))
                    (catch Exception e
                      (log/error e (str "couldn't pprint value "
                                        (:parent-value result)))
                      (pr-str (:parent-value result))))]
         :error false}

        ;; get_path - return pprinted value
        (contains? result :value)
        {:result [(or (:pretty-value result) "nil")]
         :error false}

        ;; delete_path - return removed message only
        (:removed-from result)
        {:result [(str "Removed value at path " (:removed-from result))]
         :error false}

        ;; inspect - return pprinted truncated view only
        (:tree result)
        {:result [(:tree result)]
         :error false})
      (catch Exception e
        (let [msg (str "Error formatting scratch_pad tool call: " (.getMessage e))]
          (log/error e msg)
          {:error true
           :result [msg]})))))

;; this is needed because of the special handling of edn in the default handler
(defmethod tool-system/registration-map :scratch-pad [tool-config]
  {:name (tool-system/tool-name tool-config)
   :id :scratch-pad
   :description (tool-system/tool-description tool-config)
   :schema (tool-system/tool-schema tool-config)
   :tool-fn (fn [_ params callback]
              (if (nil? params)
                (let [msg (str "Error: Received `null` arguments for scratch_pad call."
                               "Possible intermittent streaming error.")]
                  (log/debug msg)
                  (callback [msg] true))
                (try
                  (let [converted-params (tool-system/convert-java-collections params)
                        keywordized-params (-> converted-params
                                               (dissoc "value")
                                               walk/keywordize-keys)
                        {:keys [result error]}
                        (->> (get converted-params "value")
                             (assoc keywordized-params :value)
                             (tool-system/validate-inputs tool-config)
                             (tool-system/execute-tool tool-config)
                             (tool-system/format-results tool-config))]
                    (callback result error))
                  (catch Exception e
                    (log/error e (str "scratch_pad registration map error:" (or (ex-message e) "Unknown error")))
                    ;; On error, create a sequence of error messages
                    (let [error-msg (str (or (ex-message e) "Unknown error"))
                          data (ex-data e)
                          ;; Construct error messages sequence
                          error-msgs (cond-> [error-msg]
                                       ;; Add any error-details from ex-data if available
                                       (and data (:error-details data))
                                       (concat (if (sequential? (:error-details data))
                                                 (:error-details data)
                                                 [(:error-details data)])))]
                      (callback error-msgs true))))))})

(defn create-scratch-pad-tool
  [nrepl-client-atom]
  (let [working-directory (config/get-nrepl-user-dir @nrepl-client-atom)]
    {:tool-type :scratch-pad
     :nrepl-client-atom nrepl-client-atom
     :working-directory working-directory}))

(defn scratch-pad-tool
  "Returns the registration map for the scratch pad tool.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  ;; Get working directory from config
  (let [working-directory (config/get-nrepl-user-dir @nrepl-client-atom)
        load? (config/get-scratch-pad-load @nrepl-client-atom)
        filename (config/get-scratch-pad-file @nrepl-client-atom)]
    ;; persist by default
    ;; load by config
    (setup-persistence-watch! nrepl-client-atom working-directory filename)
    (when load?
      (let [existing-data (load-scratch-pad working-directory filename)]
        (if (seq existing-data)
          (swap! nrepl-client-atom assoc ::scratch-pad existing-data)
          (save-scratch-pad! working-directory filename {} nrepl-client-atom)))))

  (tool-system/registration-map (create-scratch-pad-tool nrepl-client-atom)))
