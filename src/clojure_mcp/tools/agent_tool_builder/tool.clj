(ns clojure-mcp.tools.agent-tool-builder.tool
  "MCP tool interface for dynamically creating agent tools from configuration"
  (:require
   [clojure-mcp.config :as config]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.agent-tool-builder.core :as core]
   [clojure-mcp.tools.agent-tool-builder.default-agents :as default-agents]
   [taoensso.timbre :as log]))

(defn create-single-agent-tool
  "Creates a tool registration for a single agent configuration.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   - agent-config: Map with agent configuration
   
   Returns: Tool registration map"
  [nrepl-client-atom agent-config]
  (let [{:keys [id name description]} agent-config
        ;; Create a unique tool type for this agent
        tool-type (keyword (str "configured-agent-" (clojure.core/name id)))]

    ;; Define the multimethods for this specific agent tool
    (defmethod tool-system/tool-name tool-type [_]
      name)

    (defmethod tool-system/tool-description tool-type [_]
      description)

    (defmethod tool-system/tool-schema tool-type [_]
      {:type :object
       :properties {:prompt {:type :string
                             :description "The prompt to send to the agent"}}
       :required [:prompt]})

    (defmethod tool-system/validate-inputs tool-type [_ inputs]
      (cond
        (nil? inputs)
        (throw (ex-info "Missing inputs"
                        {:error-details ["No input parameters provided"]}))

        (nil? (:prompt inputs))
        (throw (ex-info "Missing required parameter: prompt"
                        {:error-details ["The 'prompt' parameter is required"]}))

        (not (string? (:prompt inputs)))
        (throw (ex-info "Parameter 'prompt' must be a string"
                        {:error-details [(str "Got: " (type (:prompt inputs)))]}))

        :else
        inputs))

    (defmethod tool-system/execute-tool tool-type [tool {:keys [prompt]}]
      (let [{:keys [nrepl-client-atom agent-config]} tool]
        (core/chat-with-configured-agent nrepl-client-atom agent-config prompt)))

    (defmethod tool-system/format-results tool-type [_ {:keys [result error] :as _results}]
      {:result [result]
       :error error})

    ;; Return the registration map
    (tool-system/registration-map
     {:tool-type tool-type
      :nrepl-client-atom nrepl-client-atom
      :agent-config agent-config})))

(defn create-agent-tools
  "Creates tool registrations for all configured agents.
   
   This function:
   1. Loads default agent configurations (dispatch_agent, architect, code_critique, etc.)
   2. Merges any :tools-config settings for default agents  
   3. Reads user-configured agents from config.edn
   4. Merges them (user configs override defaults with same ID)
   5. Creates a separate tool for each agent
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   
   Returns: Vector of tool registration maps (one per configured agent)"
  [nrepl-client-atom]
  (let [;; Get default agents
        default-agents (default-agents/make-default-agents)

        ;; Get tool configs for potential override of default agents
        tools-config (config/get-tools-config @nrepl-client-atom)

        ;; Map tool-id keywords to agent-id keywords
        tool-id->agent-id {:dispatch_agent :dispatch-agent
                           :architect :architect
                           :code_critique :code-critique}

        ;; Merge tool configs into matching default agents
        default-agents-with-tool-config
        (mapv (fn [agent-config]
                ;; Find if there's a tool config for this agent
                (let [agent-id (:id agent-config)
                      ;; Look for tool config using both the agent ID and the tool name
                      tool-config (or (get tools-config agent-id)
                                      (get tools-config (keyword (:name agent-config)))
                                      ;; Also check the reverse mapping
                                      (some (fn [[tool-id mapped-agent-id]]
                                              (when (= mapped-agent-id agent-id)
                                                (get tools-config tool-id)))
                                            tool-id->agent-id))]
                  (default-agents/merge-tool-config-into-agent agent-config tool-config)))
              default-agents)

        ;; Get user-configured agents
        user-agents (config/get-agents-config @nrepl-client-atom)

        ;; Create maps indexed by ID for merging
        default-map (into {} (map (juxt :id identity) default-agents-with-tool-config))
        user-map (into {} (map (juxt :id identity) user-agents))

        ;; Merge: user configs override defaults with same ID
        merged-map (merge default-map user-map)

        ;; Get all unique agents
        all-agents (vals merged-map)]

    (if (empty? all-agents)
      (do
        (log/debug "No agents configured (defaults + user)")
        [])
      (do
        (log/info (str "Creating " (count all-agents) " agent tools ("
                       (count (filter #(contains? (default-agents/default-agent-ids) (:id %)) all-agents))
                       " defaults, "
                       (count (filter #(not (contains? (default-agents/default-agent-ids) (:id %))) all-agents))
                       " user-defined)"))
        (mapv #(create-single-agent-tool nrepl-client-atom %) all-agents)))))

(defn agent-tool-builder
  "Convenience function for creating agent tools.
   Same as create-agent-tools but follows naming convention of other tools.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   
   Returns: Vector of tool registration maps"
  [nrepl-client-atom]
  (create-agent-tools nrepl-client-atom))