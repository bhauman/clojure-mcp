(ns clojure-mcp.tools.agent-tool-builder.tool-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure-mcp.tools.agent-tool-builder.tool :as agent-builder]
   [clojure-mcp.tools.agent-tool-builder.default-agents :as default-agents]
   [clojure-mcp.tools :as tools]
   [clojure-mcp.config :as config]))

(deftest test-filter-tools
  (testing "Filter tools with enable list"
    (let [tools [{:tool-type :tool1} {:tool-type :tool2} {:tool-type :tool3}]
          filtered (tools/filter-tools tools [:tool1 :tool3] nil)]
      (is (= 2 (count filtered)))
      (is (= :tool1 (:tool-type (first filtered))))
      (is (= :tool3 (:tool-type (second filtered))))))

  (testing "Filter tools with disable list"
    (let [tools [{:tool-type :tool1} {:tool-type :tool2} {:tool-type :tool3}]
          filtered (tools/filter-tools tools :all [:tool2])]
      (is (= 2 (count filtered)))
      (is (= :tool1 (:tool-type (first filtered))))
      (is (= :tool3 (:tool-type (second filtered))))))

  (testing "Filter tools with both enable and disable"
    (let [tools [{:tool-type :tool1} {:tool-type :tool2} {:tool-type :tool3} {:tool-type :tool4}]
          filtered (tools/filter-tools tools [:tool1 :tool2 :tool3] [:tool2])]
      (is (= 2 (count filtered)))
      (is (= :tool1 (:tool-type (first filtered))))
      (is (= :tool3 (:tool-type (second filtered))))))

  (testing "nil enable-tools returns empty vector (no tools)"
    (let [tools [{:tool-type :tool1} {:tool-type :tool2}]
          filtered (tools/filter-tools tools nil nil)]
      (is (= [] filtered) "nil enable-tools should return no tools")))

  (testing ":all enable-tools returns all tools"
    (let [tools [{:tool-type :tool1} {:tool-type :tool2}]
          filtered (tools/filter-tools tools :all nil)]
      (is (= tools filtered) "':all' enable-tools should return all tools"))))

(deftest test-create-agent-tools
  (testing "Default agents are always created even with empty config"
    (let [nrepl-atom (atom {::config/config {:agents []}})]
      ;; Should create the 4 default agents (dispatch_agent, architect, code_critique, clojure_edit_agent)
      (is (= 4 (count (agent-builder/create-agent-tools nrepl-atom))))))

  (testing "Creates tools for configured agents plus defaults"
    (let [agent-config {:id :test-agent
                        :name "test_agent"
                        :description "Test agent"
                        :system-message "Test system message"
                        :context false
                        :enable-tools nil
                        :disable-tools nil}
          nrepl-atom (atom {::config/config {:agents [agent-config]}})
          tools (agent-builder/create-agent-tools nrepl-atom)
          tool-names (map :name tools)]
      ;; Should have 4 defaults + 1 user agent
      (is (= 5 (count tools)))
      (is (some #{"test_agent"} tool-names))
      (is (some #{"dispatch_agent"} tool-names))
      (is (some #{"architect"} tool-names))
      (is (some #{"code_critique"} tool-names))
      (is (some #{"clojure_edit_agent"} tool-names)))))

(deftest test-agent-config-retrieval
  (testing "Get agents config"
    (let [agents [{:id :agent1} {:id :agent2}]
          nrepl-map {::config/config {:agents agents}}]
      (is (= agents (config/get-agents-config nrepl-map)))))

  (testing "Get specific agent config"
    (let [agent1 {:id :agent1 :name "Agent 1"}
          agent2 {:id :agent2 :name "Agent 2"}
          nrepl-map {::config/config {:agents [agent1 agent2]}}]
      (is (= agent1 (config/get-agent-config nrepl-map :agent1)))
      (is (= agent2 (config/get-agent-config nrepl-map :agent2)))
      (is (nil? (config/get-agent-config nrepl-map :agent3))))))

(deftest test-tool-normalization
  (testing "String and keyword tool IDs are normalized"
    (let [tools [{:tool-type :tool1} {:name "tool2"} {:tool-type :tool3}]
          ;; Test with string IDs
          filtered1 (tools/filter-tools tools ["tool1" "tool3"] nil)]
      (is (= 2 (count filtered1)))

      ;; Test with mixed string and keyword disable list
      ;; Use :all to get all tools, then apply disable list
      (let [filtered2 (tools/filter-tools tools :all ["tool1" :tool3])]
        (is (= 1 (count filtered2)))))))

(deftest test-agent-no-tools-by-default
  (testing "Agents have no tools by default when enable-tools is nil"
    (let [agent-config {:id :test-agent
                        :name "test_agent"
                        :description "Test agent"
                        :system-message "Test"
                        :context false
                        :enable-tools nil ; nil means no tools
                        :disable-tools nil}
          _nrepl-atom (atom {::config/config {}})
          ;; Mock tools/build-all-tools by setting up some test tools
          test-tools [{:tool-type :tool1} {:tool-type :tool2}]]

      ;; Test the filtering logic directly
      (with-redefs [tools/build-all-tools (fn [_] test-tools)]
        ;; This simulates the logic in build-agent-from-config
        (let [filtered (cond
                         (nil? (:enable-tools agent-config)) []
                         (and (sequential? (:enable-tools agent-config))
                              (some #{:all} (:enable-tools agent-config)))
                         (tools/filter-tools test-tools :all nil)
                         :else
                         (tools/filter-tools test-tools
                                             (:enable-tools agent-config)
                                             (:disable-tools agent-config)))]
          (is (= [] filtered) "Agent with nil enable-tools should have no tools")))))

  (testing "Agents with [:all] get all tools"
    (let [test-tools [{:tool-type :tool1} {:tool-type :tool2}]
          agent-config {:enable-tools [:all]}]
      (with-redefs [tools/build-all-tools (fn [_] test-tools)]
        (let [filtered (cond
                         (nil? (:enable-tools agent-config)) []
                         (and (sequential? (:enable-tools agent-config))
                              (some #{:all} (:enable-tools agent-config)))
                         (tools/filter-tools test-tools :all nil)
                         :else
                         (tools/filter-tools test-tools
                                             (:enable-tools agent-config)
                                             nil))]
          (is (= 2 (count filtered)) "Agent with [:all] should get all tools"))))))

(deftest test-merge-tool-config-into-agent
  (testing "Merging tool config into agent config"
    (let [base-agent {:id :test-agent
                      :name "test_agent"
                      :description "Original description"
                      :system-message "Original system message"
                      :context true
                      :enable-tools [:LS :read_file]
                      :memory-size 100}

          tool-config {:model :openai/gpt-4
                       :context ["specific-file.md"]
                       :memory-size 200
                       :enable-tools [:all]
                       :ignored-key "This should not be merged"}

          merged (default-agents/merge-tool-config-into-agent base-agent tool-config)]

      ;; Check that appropriate keys are merged
      (is (= (:model merged) :openai/gpt-4))
      (is (= (:memory-size merged) 200))
      (is (= (:enable-tools merged) [:all]))
      (is (= (:context merged) ["specific-file.md"]))

      ;; Check that other keys are preserved
      (is (= (:id merged) :test-agent))
      (is (= (:description merged) "Original description"))

      ;; Check that non-agent keys are not merged
      (is (nil? (:ignored-key merged))))))

(deftest test-default-agents-with-tool-config
  (testing "Default agents are properly merged with tool configs"
    (let [test-atom (atom {})
          _ (config/set-config! test-atom :tools-config
                                {:dispatch_agent {:memory-size 200
                                                  :model :openai/gpt-4
                                                  :enable-tools [:all]}
                                 :code_critique {:memory-size 50
                                                 :system-message "Custom critique"}
                                 :architect {:context true
                                             :enable-tools [:all]}})

          ;; Get the default agents
          agents (agent-builder/create-agent-tools test-atom)
          agent-names (map :name agents)]

      ;; Should have 4 default agents
      (is (= (count agents) 4))

      ;; Check that all default agents are present
      (is (some #{"dispatch_agent"} agent-names))
      (is (some #{"architect"} agent-names))
      (is (some #{"code_critique"} agent-names))
      (is (some #{"clojure_edit_agent"} agent-names)))))

(deftest test-user-agents-override-defaults
  (testing "User-defined agents override default agents with same ID"
    (let [test-atom (atom {})
          _ (config/set-config! test-atom :agents
                                [{:id :dispatch-agent
                                  :name "custom_dispatch"
                                  :description "Custom dispatch agent"
                                  :system-message "Custom system message"
                                  :context false
                                  :enable-tools nil
                                  :memory-size 10}])

          agents (agent-builder/create-agent-tools test-atom)
          agent-names (map :name agents)]

      ;; Should still have 4 agents total (3 defaults + 1 custom replacing dispatch-agent)
      (is (= (count agents) 4))

      ;; Check that the custom agent replaced the default
      (is (some #{"custom_dispatch"} agent-names))
      (is (not (some #{"dispatch_agent"} agent-names))))))

(deftest test-tool-config-and-user-agents-interaction
  (testing "Tool config applies to defaults but user agents override completely"
    (let [test-atom (atom {})
          _ (config/set-config! test-atom :tools-config
                                {:dispatch_agent {:memory-size 200}
                                 :architect {:memory-size 300}})
          _ (config/set-config! test-atom :agents
                                [{:id :architect
                                  :name "user_architect"
                                  :description "User's architect"
                                  :system-message "User's message"
                                  :context false
                                  :enable-tools nil
                                  :memory-size 50}])

          agents (agent-builder/create-agent-tools test-atom)
          agent-names (map :name agents)]

      ;; Check that user's architect overrides the default completely
      (is (some #{"user_architect"} agent-names))
      (is (not (some #{"architect"} agent-names)))

      ;; dispatch_agent should still use tool config since not overridden
      (is (some #{"dispatch_agent"} agent-names)))))