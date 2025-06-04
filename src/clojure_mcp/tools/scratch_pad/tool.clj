(ns clojure-mcp.tools.scratch-pad.tool
  "Implementation of the scratch-pad tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.scratch-pad.core :as core]
   [clojure.tools.logging :as log]
   [clojure.walk]))

(defn get-scratch-pad
  "Gets the current scratch pad data from the nrepl-client.
   Returns an empty map if no data exists yet."
  [nrepl-client-atom]
  (get @nrepl-client-atom ::scratch-pad {}))

(defn update-scratch-pad!
  "Updates the scratch pad data in the nrepl-client-atom."
  [nrepl-client-atom f & args]
  (apply swap! nrepl-client-atom update ::scratch-pad f args))

(defmethod tool-system/tool-name :scratch-pad [_]
  "scratch_pad")

(defmethod tool-system/tool-description :scratch-pad [_]
  "A persistent scratch pad for storing structured data between tool calls. Accepts any JSON value (objects, arrays, strings, numbers, booleans, null) and stores them at nested paths using assoc_in, get_in, dissoc_in operations.

Whenever you need to make a plan, this is your goto tool.

This tool can be used to:
 * maintain todo lists
 * maintain a list of files that need to be addressed
 * store thinking and overall strategy notes
 * store a list of reminders
 * simply jotting down things that are noteworthy
 * or other proactive/creative uses that will help you accomplish the task at hand

TRACKING PLANS WITH TODO LISTS:

Recommended todo item structure:
{
  task: \"Description of the task\",
  done: false,
  priority: \"high\", // optional: \"high\", \"medium\", \"low\"
  context: \"Additional details\" // optional
}

Adding todo items:
- First item:
  op: assoc_in
  path: [\"todos\" 0]
  value: {task: \"Write tests\", done: false}
  todo: \"todos\"
  explanation: Adding first task

- Next item:
  op: assoc_in
  path: [\"todos\" 1]
  value: {task: \"Review PR\", done: false, priority: \"high\"}
  todo: \"todos\"
  explanation: Adding high priority task

Adding multiple todo items at once:
- Entire map:
  op: assoc_in
  path: [\"todos\"]
  value: {
    0: {task: \"Write tests\", done: false, priority: \"high\"},
    1: {task: \"Review PR\", done: false, priority: \"high\"},
    2: {task: \"Update docs\", done: false, priority: \"medium\"}
  }
  todo: \"todos\"
  explanation: Adding multiple todos at once

Checking off completed tasks:
- Mark as done:
  op: assoc_in
  path: [\"todos\" 0 \"done\"]
  value: true
  todo: \"todos\"
  explanation: Completed writing tests

Deleting tasks:
- Remove entire task:
  op: dissoc_in
  path: [\"todos\" 0]
  explanation: Removing completed task

- Remove specific field:
  op: dissoc_in
  path: [\"todos\" 1 \"priority\"]
  explanation: Removing priority field

Viewing todos:
- All data:
  op: tree_view
  explanation: Checking todo list

- Specific task:
  op: get_in
  path: [\"todos\" 0]
  explanation: Checking first task details

The \"todo\" parameter helps UI tools track and display task progress when working with todo lists.")

(defmethod tool-system/tool-schema :scratch-pad [_]
  {:type "object"
   :properties {"op" {:type "string"
                      :enum ["assoc_in" "get_in" "dissoc_in" "tree_view"]
                      :description "The operation to perform"}
                "path" {:type "array"
                        :items {:type ["string" "number"]}
                        :description "Path to the data location (array of string or number keys)"}
                "value" {:description "Value to store (for assoc_in). Can be any JSON value: object, array, string, number, boolean, or null."
                         :type ["object" "array" "string" "number" "boolean" "null"]}
                "explanation" {:type "string"
                               :description "Explanation of why this operation is being performed"}
                "todo" {:type "string"
                        :description "(Optional) Represents the key of the todo list being operated on ie.  \"rename_function_todos\" "}}
   :required ["op" "explanation"]})

(defmethod tool-system/validate-inputs :scratch-pad [{:keys [nrepl-client-atom]} inputs]
  (let [{:keys [op path value explanation]} inputs]

    ;; Check required parameters
    (when-not op
      (throw (ex-info "Missing required parameter: op" {:inputs inputs})))

    (when-not explanation
      (throw (ex-info "Missing required parameter: explanation" {:inputs inputs})))

    ;; Validate operation
    (when-not (#{"assoc_in" "get_in" "dissoc_in" "tree_view"} op)
      (throw (ex-info "Invalid operation. Must be one of: assoc_in, get_in, dissoc_in, tree_view"
                      {:op op :inputs inputs})))

    ;; Operation-specific validation
    (case op
      "assoc_in" (do
                   (when-not path
                     (throw (ex-info "Missing required parameter for assoc_in: path" {:inputs inputs})))
                   (when-not (contains? inputs :value)
                     (throw (ex-info "Missing required parameter for assoc_in: value" {:inputs inputs}))))
      ("get_in" "dissoc_in") (when-not path
                               (throw (ex-info (str "Missing required parameter for " op ": path")
                                               {:inputs inputs})))
      "tree_view" nil)

    ;; Validate path has at least one element when provided
    (when (and path (empty? path))
      (throw (ex-info "Path must have at least one element" {:path path :inputs inputs})))

    ;; Validate path elements are strings or numbers
    (when path
      (doseq [element path]
        (when-not (or (string? element) (number? element))
          (throw (ex-info "Path elements must be strings or numbers"
                          {:element element :type (type element) :path path})))))

    ;; Convert path to vector if needed (MCP provides as array)
    (if path
      (assoc inputs :path (vec path))
      inputs)))

(defmethod tool-system/execute-tool :scratch-pad [{:keys [nrepl-client-atom]} {:keys [op path value explanation todo]}]
  (try
    (let [current-data (get-scratch-pad nrepl-client-atom)
          result (case op
                   "assoc_in" (let [new-data (core/assoc-in-data current-data path value)]
                                (update-scratch-pad! nrepl-client-atom (constantly new-data))
                                {:stored-at path
                                 :value value
                                 :todo todo})

                   "get_in" {:path path
                             :value (core/get-in-data current-data path)
                             :found (some? (core/get-in-data current-data path))}

                   "dissoc_in" (let [new-data (core/dissoc-in-data current-data path)]
                                 (update-scratch-pad! nrepl-client-atom (constantly new-data))
                                 {:removed-from path})

                   "tree_view" {:tree (core/tree-view current-data)})]
      {:result result
       :explanation explanation
       :error false})
    (catch Exception e
      {:error true
       :message (str "Error executing scratch pad operation: " (.getMessage e))})))

;; this is convoluted this can be stream lined as most of this is imply echoing back what was sent
(defmethod tool-system/format-results :scratch-pad [_ {:keys [error message result explanation]}]
  (if error
    {:result [message]
     :error true}
    (let [op-type (cond
                    (:stored-at result) "assoc_in"
                    (:removed-from result) "dissoc_in"
                    (contains? result :value) "get_in"
                    (:tree result) "tree_view")]
      {:result [(case op-type
                  "assoc_in" (str "Stored value at path " (:stored-at result)
                                  (when (:todo result)
                                    (str " (todo: " (:todo result) ")")))
                  "get_in" (if (:found result)
                             (str "Value at " (:path result) ": " (pr-str (:value result)))
                             (str "No value found at path " (:path result)))
                  "dissoc_in" (str "Removed value at path " (:removed-from result))
                  "tree_view" (str "Scratch pad contents:\n" (:tree result)))
                (str "\nReason: " explanation)]
       :error false})))

;; this is needed because of the special handling of edn in the default handler
(defmethod tool-system/registration-map :scratch-pad [tool-config]
  {:name (tool-system/tool-name tool-config)
   :description (tool-system/tool-description tool-config)
   :schema (tool-system/tool-schema tool-config)
   :tool-fn (fn [_ params callback]
              (try
                (let [converted-params (tool-system/convert-java-collections params)
                      keywordized-params (-> converted-params
                                             (dissoc "value")
                                             tool-system/keywordize-keys-preserve-underscores)
                      {:keys [result error]}
                      (->> (get converted-params "value")
                           (assoc keywordized-params :value)
                           (tool-system/validate-inputs tool-config)
                           (tool-system/execute-tool tool-config)
                           (tool-system/format-results tool-config))]
                  (callback result error))
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

(defn create-scratch-pad-tool [nrepl-client-atom]
  {:tool-type :scratch-pad
   :nrepl-client-atom nrepl-client-atom})

(defn scratch-pad-tool
  "Returns the registration map for the scratch pad tool.
   
   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-scratch-pad-tool nrepl-client-atom)))

(comment
  ;; Usage examples with JSON values:

  ;; Store a simple string
  {:op "assoc_in"
   :path ["user" "name"]
   :value "Alice"
   :explanation "Setting user name"}

  ;; Store an object (JSON object becomes Clojure map with string keys)
  {:op "assoc_in"
   :path ["config"]
   :value {"theme" "dark" "fontSize" 14}
   :explanation "Storing user preferences"}

  ;; Store an array
  {:op "assoc_in"
   :path ["tags"]
   :value ["clojure" "mcp" "tools"]
   :explanation "Setting project tags"}

  ;; === TODO LIST WORKFLOW EXAMPLE ===

  ;; 1. Add first todo item (object with string keys)
  {:op "assoc_in"
   :path ["todos" 0]
   :value {"task" "Implement user authentication" "done" false "priority" "high"}
   :todo "todos"
   :explanation "Starting authentication work"}

  ;; 2. Add second todo
  {:op "assoc_in"
   :path ["todos" 1]
   :value {"task" "Write unit tests" "done" false}
   :todo "todos"
   :explanation "Adding testing task"}

  ;; 3. Check off first task (boolean value)
  {:op "assoc_in"
   :path ["todos" 0 "done"]
   :value true
   :todo "todos"
   :explanation "Completed authentication implementation"}

  ;; 4. Update task with additional context
  {:op "assoc_in"
   :path ["todos" 0 "context"]
   :value "Used OAuth2 with JWT tokens"
   :explanation "Adding implementation details"}

  ;; 5. Remove completed task
  {:op "dissoc_in"
   :path ["todos" 0]
   :explanation "Cleaning up completed task"}

  ;; 6. View all todos
  {:op "tree_view"
   :explanation "Reviewing remaining tasks"}

  ;; === OTHER OPERATIONS ===

  ;; Get a specific value
  {:op "get_in"
   :path ["user" "name"]
   :explanation "Retrieving user name"}

  ;; Store nested data
  {:op "assoc_in"
   :path ["metrics" "performance"]
   :value {"cpu" 45.2 "memory" 78 "disk" 92}
   :explanation "Recording system metrics"}

  ;; Remove a value
  {:op "dissoc_in"
   :path ["config" "old-setting"]
   :explanation "Removing deprecated config"})
