(ns clojure-mcp.config.profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]))

;; Tests for config profile loading functionality

(deftest test-load-config-profile
  (testing "Loading existing profile returns parsed EDN map"
    (let [profile-config (config/load-config-profile :cli-assist)]
      (is (map? profile-config))
      (is (contains? profile-config :disable-tools))
      (is (contains? profile-config :write-file-guard))
      (is (false? (:write-file-guard profile-config)))))

  (testing "Profile can be loaded with keyword"
    (let [profile-config (config/load-config-profile :cli-assist)]
      (is (map? profile-config))
      (is (seq profile-config))))

  (testing "Profile can be loaded with string"
    (let [profile-config (config/load-config-profile "cli-assist")]
      (is (map? profile-config))
      (is (seq profile-config))))

  (testing "Profile can be loaded with symbol"
    (let [profile-config (config/load-config-profile 'cli-assist)]
      (is (map? profile-config))
      (is (seq profile-config))))

  (testing "Missing profile returns empty map"
    (let [profile-config (config/load-config-profile :nonexistent-profile)]
      (is (= {} profile-config))))

  (testing "nil profile returns nil"
    (let [profile-config (config/load-config-profile nil)]
      (is (nil? profile-config)))))

(deftest test-apply-config-profile
  (testing "Applying profile merges on top of base config"
    (let [base-config {:write-file-guard :full-read
                       :cljfmt true
                       :some-other-setting "value"}
          result (config/apply-config-profile base-config :cli-assist)]
      ;; Profile values should override base
      (is (false? (:write-file-guard result)))
      ;; Non-overlapping base values should be preserved
      (is (true? (:cljfmt result)))
      (is (= "value" (:some-other-setting result)))
      ;; Profile values should be present
      (is (vector? (:disable-tools result)))))

  (testing "Applying nil profile returns config unchanged"
    (let [base-config {:write-file-guard :partial-read
                       :cljfmt true}
          result (config/apply-config-profile base-config nil)]
      (is (= base-config result))))

  (testing "Applying missing profile returns config unchanged"
    (let [base-config {:write-file-guard :partial-read
                       :cljfmt true}
          result (config/apply-config-profile base-config :nonexistent-profile)]
      (is (= base-config result))))

  (testing "Deep merge works for nested maps"
    (let [base-config {:tools-config {:bash {:timeout 5000}
                                      :grep {:max-results 100}}}
          ;; Create a temporary profile-like config to test deep merge
          profile-config {:tools-config {:bash {:enabled false}}}
          result (config/deep-merge base-config profile-config)]
      ;; Nested values should be merged, not replaced
      (is (= 5000 (get-in result [:tools-config :bash :timeout])))
      (is (false? (get-in result [:tools-config :bash :enabled])))
      (is (= 100 (get-in result [:tools-config :grep :max-results]))))))

(deftest test-cli-assist-profile-contents
  (testing "cli-assist profile disables expected tools"
    (let [profile-config (config/load-config-profile :cli-assist)
          disable-tools (:disable-tools profile-config)]
      ;; Should disable file operation tools and agent tools
      (is (some #{:read_file} disable-tools))
      (is (some #{:bash} disable-tools))
      (is (some #{:scratch_pad} disable-tools))
      (is (some #{:clojure_edit_agent} disable-tools))
      ;; Should NOT disable these tools (they remain enabled)
      (is (not (some #{:clojure_eval} disable-tools)))
      (is (not (some #{:clojure_edit} disable-tools)))
      (is (not (some #{:list_nrepl_ports} disable-tools)))))

  (testing "cli-assist profile disables write-file-guard"
    (let [profile-config (config/load-config-profile :cli-assist)]
      (is (false? (:write-file-guard profile-config))))))

(deftest test-cli-assist-full-profile-contents
  (testing "cli-assist-full promotes clojure-mcp edit/read tools to first-class"
    (let [profile-config (config/load-config-profile :cli-assist-full)
          disable-tools (:disable-tools profile-config)]
      ;; First-class tools must NOT be disabled (read_file is the key delta vs cli-assist)
      (is (not (some #{:read_file} disable-tools)))
      (is (not (some #{:clojure_edit} disable-tools)))
      (is (not (some #{:clojure_edit_replace_sexp} disable-tools)))
      (is (not (some #{:paren_repair} disable-tools)))
      (is (not (some #{:clojure_eval} disable-tools)))
      (is (not (some #{:list_nrepl_ports} disable-tools)))
      ;; Position/whole-file editors stay disabled so :write-file-guard false is safe
      (is (some #{:file_edit} disable-tools))
      (is (some #{:file_write} disable-tools))
      ;; Tools the host CLI already provides stay disabled
      (is (some #{:grep} disable-tools))
      (is (some #{:glob_files} disable-tools))
      (is (some #{:bash} disable-tools))
      (is (some #{:clojure_inspect_project} disable-tools))))

  (testing "cli-assist-full restores first-class descriptions (no :tools-config override)"
    (let [profile-config (config/load-config-profile :cli-assist-full)]
      (is (not (contains? profile-config :tools-config)))))

  (testing "cli-assist-full disables write-file-guard"
    (let [profile-config (config/load-config-profile :cli-assist-full)]
      (is (false? (:write-file-guard profile-config)))))

  (testing "cli-assist-full provides its own steering mcp-instructions"
    (let [profile-config (config/load-config-profile :cli-assist-full)]
      (is (string? (:mcp-instructions profile-config))))))
