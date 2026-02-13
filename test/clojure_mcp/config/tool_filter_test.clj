(ns clojure-mcp.config.tool-filter-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]))

;; Tests for ENABLE_TOOLS and DISABLE_TOOLS environment variable support

(deftest test-enable-tools-env-var
  (testing "ENABLE_TOOLS env var overrides config"
    (binding [config/*env-overrides* {"ENABLE_TOOLS" "bash,eval_code,read_file"}]
      (let [nrepl-map {::config/config {:enable-tools [:other_tool :another_tool]}}]
        ;; Env var should override config
        (is (true? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :eval_code)))
        (is (true? (config/tool-id-enabled? nrepl-map :read_file)))
        (is (false? (config/tool-id-enabled? nrepl-map :other_tool)))
        (is (false? (config/tool-id-enabled? nrepl-map :grep))))))

  (testing "ENABLE_TOOLS env var with spaces around commas"
    (binding [config/*env-overrides* {"ENABLE_TOOLS" "bash , eval_code , read_file"}]
      (let [nrepl-map {::config/config {}}]
        (is (true? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :eval_code)))
        (is (true? (config/tool-id-enabled? nrepl-map :read_file)))
        (is (false? (config/tool-id-enabled? nrepl-map :grep))))))

  (testing "ENABLE_TOOLS env var normalizes hyphens to underscores"
    (binding [config/*env-overrides* {"ENABLE_TOOLS" "file-edit,eval-code,read-file"}]
      (let [nrepl-map {::config/config {}}]
        ;; Hyphens in env var should match underscore tool IDs
        (is (true? (config/tool-id-enabled? nrepl-map :file_edit)))
        (is (true? (config/tool-id-enabled? nrepl-map :eval_code)))
        (is (true? (config/tool-id-enabled? nrepl-map :read_file)))
        (is (false? (config/tool-id-enabled? nrepl-map :grep))))))

  (testing "Empty ENABLE_TOOLS env var is ignored (config used)"
    (binding [config/*env-overrides* {"ENABLE_TOOLS" ""}]
      (let [nrepl-map {::config/config {:enable-tools [:bash :grep]}}]
        ;; Empty string should be ignored, config should be used
        (is (true? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep)))
        (is (false? (config/tool-id-enabled? nrepl-map :eval-code))))))

  (testing "No env var uses config"
    (binding [config/*env-overrides* {}]
      (let [nrepl-map {::config/config {:enable-tools [:bash :grep]}}]
        (is (true? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep)))
        (is (false? (config/tool-id-enabled? nrepl-map :eval-code)))))))

(deftest test-disable-tools-env-var
  (testing "DISABLE_TOOLS env var overrides config"
    (binding [config/*env-overrides* {"DISABLE_TOOLS" "bash,dispatch_agent"}]
      (let [nrepl-map {::config/config {:disable-tools [:other_tool]}}]
        ;; Env var should override config
        (is (false? (config/tool-id-enabled? nrepl-map :bash)))
        (is (false? (config/tool-id-enabled? nrepl-map :dispatch_agent)))
        (is (true? (config/tool-id-enabled? nrepl-map :other_tool))) ; config ignored
        (is (true? (config/tool-id-enabled? nrepl-map :grep))))))

  (testing "DISABLE_TOOLS env var with spaces"
    (binding [config/*env-overrides* {"DISABLE_TOOLS" " bash , dispatch_agent "}]
      (let [nrepl-map {::config/config {}}]
        (is (false? (config/tool-id-enabled? nrepl-map :bash)))
        (is (false? (config/tool-id-enabled? nrepl-map :dispatch_agent)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep))))))

  (testing "DISABLE_TOOLS env var normalizes hyphens to underscores"
    (binding [config/*env-overrides* {"DISABLE_TOOLS" "dispatch-agent,file-edit"}]
      (let [nrepl-map {::config/config {}}]
        ;; Hyphens in env var should match underscore tool IDs
        (is (false? (config/tool-id-enabled? nrepl-map :dispatch_agent)))
        (is (false? (config/tool-id-enabled? nrepl-map :file_edit)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep))))))

  (testing "Empty DISABLE_TOOLS env var is ignored (config used)"
    (binding [config/*env-overrides* {"DISABLE_TOOLS" ""}]
      (let [nrepl-map {::config/config {:disable-tools [:bash]}}]
        ;; Empty string should be ignored, config should be used
        (is (false? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep)))))))

(deftest test-enable-and-disable-tools-env-vars
  (testing "Both env vars can be used together"
    (binding [config/*env-overrides* {"ENABLE_TOOLS" "bash,grep,eval_code"
                                       "DISABLE_TOOLS" "bash"}]
      (let [nrepl-map {::config/config {}}]
        ;; bash is in enable but also in disable - disable wins
        (is (false? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep)))
        (is (true? (config/tool-id-enabled? nrepl-map :eval_code)))
        (is (false? (config/tool-id-enabled? nrepl-map :read_file)))))) ; not in enable list

  (testing "ENABLE_TOOLS env var with DISABLE_TOOLS from config"
    (binding [config/*env-overrides* {"ENABLE_TOOLS" "bash,grep,eval_code"}]
      (let [nrepl-map {::config/config {:disable-tools [:bash]}}]
        ;; ENABLE from env, DISABLE from config
        (is (false? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep)))
        (is (true? (config/tool-id-enabled? nrepl-map :eval_code))))))

  (testing "DISABLE_TOOLS env var with ENABLE_TOOLS from config"
    (binding [config/*env-overrides* {"DISABLE_TOOLS" "bash"}]
      (let [nrepl-map {::config/config {:enable-tools [:bash :grep :eval_code]}}]
        ;; ENABLE from config, DISABLE from env
        (is (false? (config/tool-id-enabled? nrepl-map :bash)))
        (is (true? (config/tool-id-enabled? nrepl-map :grep)))
        (is (true? (config/tool-id-enabled? nrepl-map :eval_code)))
        (is (false? (config/tool-id-enabled? nrepl-map :read_file)))))))

(deftest test-tool-id-enabled?
  (testing "No configuration - all tools enabled"
    (let [nrepl-map {::config/config {}}]
      (is (true? (config/tool-id-enabled? nrepl-map :clojure-eval)))
      (is (true? (config/tool-id-enabled? nrepl-map :bash)))
      (is (true? (config/tool-id-enabled? nrepl-map :any-tool)))))

  (testing "Empty enable list - no tools enabled"
    (let [nrepl-map {::config/config {:enable-tools []}}]
      (is (false? (config/tool-id-enabled? nrepl-map :clojure-eval)))
      (is (false? (config/tool-id-enabled? nrepl-map :bash)))
      (is (false? (config/tool-id-enabled? nrepl-map :any-tool)))))

  (testing "Enable specific tools"
    (let [nrepl-map {::config/config {:enable-tools [:clojure-eval :read-file :file-write]}}]
      (is (true? (config/tool-id-enabled? nrepl-map :clojure-eval)))
      (is (true? (config/tool-id-enabled? nrepl-map :read-file)))
      (is (true? (config/tool-id-enabled? nrepl-map :file-write)))
      (is (false? (config/tool-id-enabled? nrepl-map :bash)))
      (is (false? (config/tool-id-enabled? nrepl-map :grep)))))

  (testing "Disable specific tools"
    (let [nrepl-map {::config/config {:disable-tools [:bash :dispatch-agent]}}]
      (is (true? (config/tool-id-enabled? nrepl-map :clojure-eval)))
      (is (true? (config/tool-id-enabled? nrepl-map :read-file)))
      (is (false? (config/tool-id-enabled? nrepl-map :bash)))
      (is (false? (config/tool-id-enabled? nrepl-map :dispatch-agent)))))

  (testing "Enable and disable lists - disable takes precedence"
    (let [nrepl-map {::config/config {:enable-tools [:clojure-eval :bash :read-file]
                                      :disable-tools [:bash]}}]
      (is (true? (config/tool-id-enabled? nrepl-map :clojure-eval)))
      (is (false? (config/tool-id-enabled? nrepl-map :bash))) ; disabled even though enabled
      (is (true? (config/tool-id-enabled? nrepl-map :read-file)))
      (is (false? (config/tool-id-enabled? nrepl-map :grep))))) ; not in enable list

  (testing "String tool IDs are converted to keywords"
    (let [nrepl-map {::config/config {:enable-tools ["clojure-eval" "read-file"]
                                      :disable-tools ["bash"]}}]
      (is (true? (config/tool-id-enabled? nrepl-map "clojure-eval")))
      (is (true? (config/tool-id-enabled? nrepl-map :clojure-eval)))
      (is (false? (config/tool-id-enabled? nrepl-map "bash")))
      (is (false? (config/tool-id-enabled? nrepl-map :bash))))))

(deftest test-prompt-name-enabled?
  (testing "No configuration - all prompts enabled"
    (let [nrepl-map {::config/config {}}]
      (is (true? (config/prompt-name-enabled? nrepl-map "clojure_repl_system_prompt")))
      (is (true? (config/prompt-name-enabled? nrepl-map "chat-session-summarize")))
      (is (true? (config/prompt-name-enabled? nrepl-map "any-prompt")))))

  (testing "Empty enable list - no prompts enabled"
    (let [nrepl-map {::config/config {:enable-prompts []}}]
      (is (false? (config/prompt-name-enabled? nrepl-map "clojure_repl_system_prompt")))
      (is (false? (config/prompt-name-enabled? nrepl-map "chat-session-summarize")))
      (is (false? (config/prompt-name-enabled? nrepl-map "any-prompt")))))

  (testing "Enable specific prompts"
    (let [nrepl-map {::config/config {:enable-prompts ["clojure_repl_system_prompt" "chat-session-summarize"]}}]
      (is (true? (config/prompt-name-enabled? nrepl-map "clojure_repl_system_prompt")))
      (is (true? (config/prompt-name-enabled? nrepl-map "chat-session-summarize")))
      (is (false? (config/prompt-name-enabled? nrepl-map "chat-session-resume")))
      (is (false? (config/prompt-name-enabled? nrepl-map "create-update-project-summary")))))

  (testing "Disable specific prompts"
    (let [nrepl-map {::config/config {:disable-prompts ["chat-session-summarize" "chat-session-resume"]}}]
      (is (true? (config/prompt-name-enabled? nrepl-map "clojure_repl_system_prompt")))
      (is (false? (config/prompt-name-enabled? nrepl-map "chat-session-summarize")))
      (is (false? (config/prompt-name-enabled? nrepl-map "chat-session-resume")))))

  (testing "Enable and disable lists - disable takes precedence"
    (let [nrepl-map {::config/config {:enable-prompts ["clojure_repl_system_prompt" "chat-session-summarize"]
                                      :disable-prompts ["chat-session-summarize"]}}]
      (is (true? (config/prompt-name-enabled? nrepl-map "clojure_repl_system_prompt")))
      (is (false? (config/prompt-name-enabled? nrepl-map "chat-session-summarize"))) ; disabled even though enabled
      (is (false? (config/prompt-name-enabled? nrepl-map "chat-session-resume"))))) ; not in enable list

  (testing "Prompt names work with strings only"
    (let [nrepl-map {::config/config {:enable-prompts ["clojure_repl_system_prompt"]
                                      :disable-prompts []}}]
      (is (true? (config/prompt-name-enabled? nrepl-map "clojure_repl_system_prompt")))))

  (testing "String prompt names in config"
    (let [nrepl-map {::config/config {:enable-prompts ["create-update-project-summary" "clojure_repl_system_prompt"]}}]
      (is (true? (config/prompt-name-enabled? nrepl-map "create-update-project-summary")))
      (is (true? (config/prompt-name-enabled? nrepl-map "clojure_repl_system_prompt"))))))

(deftest test-resource-name-enabled?
  (testing "No configuration - all resources enabled"
    (let [nrepl-map {::config/config {}}]
      (is (true? (config/resource-name-enabled? nrepl-map "PROJECT_SUMMARY.md")))
      (is (true? (config/resource-name-enabled? nrepl-map "README.md")))
      (is (true? (config/resource-name-enabled? nrepl-map "any-resource")))))

  (testing "Empty enable list - no resources enabled"
    (let [nrepl-map {::config/config {:enable-resources []}}]
      (is (false? (config/resource-name-enabled? nrepl-map "PROJECT_SUMMARY.md")))
      (is (false? (config/resource-name-enabled? nrepl-map "README.md")))
      (is (false? (config/resource-name-enabled? nrepl-map "any-resource")))))

  (testing "Enable specific resources"
    (let [nrepl-map {::config/config {:enable-resources ["PROJECT_SUMMARY.md" "README.md" "Clojure Project Info"]}}]
      (is (true? (config/resource-name-enabled? nrepl-map "PROJECT_SUMMARY.md")))
      (is (true? (config/resource-name-enabled? nrepl-map "README.md")))
      (is (true? (config/resource-name-enabled? nrepl-map "Clojure Project Info"))) ; with spaces
      (is (false? (config/resource-name-enabled? nrepl-map "CLAUDE.md")))
      (is (false? (config/resource-name-enabled? nrepl-map "LLM_CODE_STYLE.md")))))

  (testing "Disable specific resources"
    (let [nrepl-map {::config/config {:disable-resources ["CLAUDE.md" "LLM_CODE_STYLE.md"]}}]
      (is (true? (config/resource-name-enabled? nrepl-map "PROJECT_SUMMARY.md")))
      (is (true? (config/resource-name-enabled? nrepl-map "README.md")))
      (is (false? (config/resource-name-enabled? nrepl-map "CLAUDE.md")))
      (is (false? (config/resource-name-enabled? nrepl-map "LLM_CODE_STYLE.md")))))

  (testing "Enable and disable lists - disable takes precedence"
    (let [nrepl-map {::config/config {:enable-resources ["PROJECT_SUMMARY.md" "README.md" "CLAUDE.md"]
                                      :disable-resources ["CLAUDE.md"]}}]
      (is (true? (config/resource-name-enabled? nrepl-map "PROJECT_SUMMARY.md")))
      (is (true? (config/resource-name-enabled? nrepl-map "README.md")))
      (is (false? (config/resource-name-enabled? nrepl-map "CLAUDE.md"))) ; disabled even though enabled
      (is (false? (config/resource-name-enabled? nrepl-map "LLM_CODE_STYLE.md"))))) ; not in enable list

  (testing "Resource names work with strings only"
    (let [nrepl-map {::config/config {:enable-resources ["PROJECT_SUMMARY.md" "README.md" "Clojure Project Info"]
                                      :disable-resources []}}]
      (is (true? (config/resource-name-enabled? nrepl-map "PROJECT_SUMMARY.md")))
      (is (true? (config/resource-name-enabled? nrepl-map "README.md")))
      ;; Handles resource names with spaces
      (is (true? (config/resource-name-enabled? nrepl-map "Clojure Project Info"))))))



