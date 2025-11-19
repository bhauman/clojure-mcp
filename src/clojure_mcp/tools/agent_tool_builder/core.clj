(ns clojure-mcp.tools.agent-tool-builder.core
  "Core functionality for building agent tools from configuration"
  (:require
   [clojure-mcp.config :as config]
   [clojure-mcp.agent.general-agent :as general-agent]
   [clojure-mcp.agent.langchain :as chain]
   [clojure-mcp.agent.langchain.model :as model]
   [clojure-mcp.tools :as tools]
   [clojure-mcp.tools.agent-tool-builder.file-changes :as file-changes]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defn build-agent-from-config
  "Builds an agent from a configuration map.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   - agent-config: Map with :id, :name, :description, :system-message, :context, 
                   :model, :enable-tools, :disable-tools, :memory-size, :listeners
   
   Note: If :enable-tools is not specified or is nil, the agent will have NO tools.
         Specify :enable-tools with desired tool IDs to give the agent access to tools.
         Use :enable-tools [:all] to give access to all available tools.
   
   Returns: Agent service map"
  [nrepl-client-atom agent-config]
  (let [{:keys [_id name _description system-message context
                model enable-tools disable-tools memory-size listeners]} agent-config

        ;; Prepare isolated atom for agent tools
        agent-atom (general-agent/prep-agent-system-atom nrepl-client-atom)

        working-directory (config/get-nrepl-user-dir @nrepl-client-atom)

        ;; Build tools with isolated atom
        all-available-tools (tools/build-all-tools agent-atom)

        ;; Handle tool filtering - filter-tools now properly handles nil, :all, and lists
        filtered-tools (if (and (sequential? enable-tools)
                                (some #{:all} enable-tools))
                         ;; If [:all] is in the list, treat as :all
                         (tools/filter-tools all-available-tools :all disable-tools)
                         ;; Otherwise let filter-tools handle it (nil returns [], list returns filtered)
                         (tools/filter-tools all-available-tools enable-tools disable-tools))

        ;; Build context strings based on config using original atom for config access
        context-strings (general-agent/build-context-strings
                         nrepl-client-atom working-directory context)

        ;; Get or create model using original atom for config
        ;; Pass listeners through to model creation if provided
        final-model (cond
                      ;; If model is already a built object
                      (and model (not (keyword? model)) (not (string? model)))
                      model

                      ;; If model is a keyword/string reference, pass listeners
                      (or (keyword? model) (string? model))
                      (if listeners
                        (model/create-model-from-config @nrepl-client-atom model {:listeners listeners})
                        (model/create-model-from-config @nrepl-client-atom model))

                      ;; Default to available models, include listeners if provided
                      :else
                      (let [builder (chain/agent-model)]
                        (when listeners
                          (.listeners builder listeners))
                        (some-> builder (.build))))]

    (log/info (str "Building agent '" name "' with "
                   (count filtered-tools) " tools"
                   (if memory-size
                     (str " and memory-size: " memory-size)
                     " (stateless)")
                   (if listeners
                     (str " and " (count listeners) " listeners")
                     "")))

    (general-agent/create-general-agent
     {:system-prompt system-message
      :context context-strings
      :tools filtered-tools
      :model final-model
      :memory-size memory-size})))

(defn get-or-create-agent
  "Gets a cached agent or creates a new one.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   - agent-config: Agent configuration map
   
   Returns: Agent service map"
  [nrepl-client-atom agent-config]
  (let [cache-key (keyword (str "agent-tool-builder-" (name (:id agent-config))))
        cached-agent (get @nrepl-client-atom cache-key)]

    (or cached-agent
        (let [new-agent (build-agent-from-config nrepl-client-atom agent-config)]
          ;; Cache the agent
          (swap! nrepl-client-atom assoc cache-key new-agent)
          new-agent))))

(defn chat-with-configured-agent
  "Chat with an agent built from configuration.
   
   Args:
   - nrepl-client-atom: The nREPL client atom
   - agent-config: Agent configuration map
   - prompt: User message
   
   Returns: Map with :result and :error"
  [nrepl-client-atom agent-config prompt]
  ;; Reset changed files tracking if enabled
  (when (file-changes/should-track-changes? agent-config)
    (file-changes/reset-changed-files! nrepl-client-atom))

  (let [agent (get-or-create-agent nrepl-client-atom agent-config)
        result (general-agent/chat-with-agent agent prompt)]

    ;; Generate and prepend file diffs if tracking is enabled
    (if (file-changes/should-track-changes? agent-config)
      (let [diffs (file-changes/generate-all-diffs nrepl-client-atom)]
        (if (and (not (:error result)) (not (str/blank? diffs)))
          (update result :result #(str diffs "\n---\n\n" %))
          result))
      result)))
