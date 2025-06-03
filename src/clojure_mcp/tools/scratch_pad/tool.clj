(ns clojure-mcp.tools.scratch-pad.tool
  "Implementation of the scratch-pad tool using the tool-system multimethod approach."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.scratch-pad.core :as core]))

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
  "A persistent scratch pad for storing structured data between tool calls. Supports storing and retrieving data at nested paths using assoc_in, get_in, dissoc_in operations. Automatically parses EDN values, falling back to strings.

Whenever you need to store data or make a plan this is your goto tool.

TRACKING PLANS WITH TODO LISTS:

Recommended todo item schema:
{:task \"Description of the task\"
 :done false
 :priority :high/:medium/:low (optional)
 :context \"Additional details\" (optional)}

Adding todo items:
- First item: {\"op\": \"assoc_in\", \"path\": [\"todos\", \"0\"], \"value\": \"{:task \\\"Write tests\\\" :done false}\", \"todo\": \"todos\", \"explanation\": \"Adding first task\"}
- Next item: {\"op\": \"assoc_in\", \"path\": [\"todos\", \"1\"], \"value\": \"{:task \\\"Review PR\\\" :done false :priority :high}\", \"todo\": \"todos\", \"explanation\": \"Adding high priority task\"}

Adding multiple todo items at once:
- Entire map: {\"op\": \"assoc_in\", \"path\": [\"todos\"], \"value\": \"{0 {:task \\\"Write tests\\\" :done false :priority :high} 1 {:task \\\"Review PR\\\" :done false :priority :high} 2 {:task \\\"Update docs\\\" :done false :priority :medium}}\", \"todo\": \"todos\", \"explanation\": \"Adding multiple todos at once\"}

Checking off completed tasks:
- Mark as done: {\"op\": \"assoc_in\", \"path\": [\"todos\", \"0\", \":done\"], \"value\": \"true\", \"todo\": \"todos\", \"explanation\": \"Completed writing tests\"}

Deleting tasks:
- Remove entire task: {\"op\": \"dissoc_in\", \"path\": [\"todos\", \"0\"], \"explanation\": \"Removing completed task\"}
- Remove specific field: {\"op\": \"dissoc_in\", \"path\": [\"todos\", \"1\", \":priority\"], \"explanation\": \"Removing priority field\"}

Viewing todos:
- All data: {\"op\": \"tree_view\", \"explanation\": \"Checking todo list\"}
- Specific task: {\"op\": \"get_in\", \"path\": [\"todos\", \"0\"], \"explanation\": \"Checking first task details\"}

The \"todo\" parameter helps UI tools track and display task progress when working with todo lists.")

(defmethod tool-system/tool-schema :scratch-pad [_]
  {:type "object"
   :properties {"op" {:type "string"
                      :enum ["assoc_in" "get_in" "dissoc_in" "tree_view"]
                      :description "The operation to perform"}
                "path" {:type "array"
                        :items {:type "string"}
                        :description "Path to the data location (array of keys)"}
                "value" {:type "string"
                         :description "Value to store (for assoc_in). Can be EDN or plain string."}
                "explanation" {:type "string"
                               :description "Explanation of why this operation is being performed"}
                "todo" {:type "string"
                        :description "Optional key for todo list tracking"}}
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

    ;; Convert path to keywords if they look like keywords
    (let [parsed-path (when path
                        (mapv core/parse-path-element path))]
      (assoc inputs :path parsed-path))))

(defmethod tool-system/execute-tool :scratch-pad [{:keys [nrepl-client-atom]} {:keys [op path value explanation todo]}]
  (try
    (let [current-data (get-scratch-pad nrepl-client-atom)
          result (case op
                   "assoc_in" (let [new-data (core/assoc-in-data current-data path value)]
                                (update-scratch-pad! nrepl-client-atom (constantly new-data))
                                {:stored-at path
                                 :value (core/parse-value value)
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
  ;; Usage examples:

  ;; Store a simple value
  {:op "assoc_in"
   :path ["user" "name"]
   :value "Alice"
   :explanation "Setting user name"}

  ;; Store a map (EDN parsed)
  {:op "assoc_in"
   :path ["config" "settings"]
   :value "{:theme \"dark\" :font-size 14}"
   :explanation "Storing user preferences"}

  ;; === TODO LIST WORKFLOW EXAMPLE ===

  ;; 1. Add first todo item
  {:op "assoc_in"
   :path ["todos" "0"]
   :value "{:task \"Implement user authentication\" :done false :priority :high}"
   :todo "todos"
   :explanation "Starting authentication work"}

  ;; 2. Add second todo
  {:op "assoc_in"
   :path ["todos" "1"]
   :value "{:task \"Write unit tests\" :done false}"
   :todo "todos"
   :explanation "Adding testing task"}

  ;; 3. Check off first task
  {:op "assoc_in"
   :path ["todos" "0" ":done"]
   :value "true"
   :todo "todos"
   :explanation "Completed authentication implementation"}

  ;; 4. Update task with additional context
  {:op "assoc_in"
   :path ["todos" "0" ":context"]
   :value "\"Used OAuth2 with JWT tokens\""
   :explanation "Adding implementation details"}

  ;; 5. Remove completed task
  {:op "dissoc_in"
   :path ["todos" "0"]
   :explanation "Cleaning up completed task"}

  ;; 6. View all todos
  {:op "tree_view"
   :explanation "Reviewing remaining tasks"}

  ;; === OTHER OPERATIONS ===

  ;; Get a specific value
  {:op "get_in"
   :path ["user" "name"]
   :explanation "Retrieving user name"}

  ;; Remove a value
  {:op "dissoc_in"
   :path ["config" "old-setting"]
   :explanation "Removing deprecated config"})
