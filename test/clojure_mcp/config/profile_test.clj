(ns clojure-mcp.config.profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]))

;; Tests for config profile loading functionality

(deftest test-load-config-profile
  (testing "Loading existing profile returns parsed EDN map"
    (let [profile-config (config/load-config-profile :cli-assist)]
      (is (map? profile-config))
      (is (contains? profile-config :enable-tools))
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
      (is (vector? (:enable-tools result)))))

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
  (testing "cli-assist profile has expected tools"
    (let [profile-config (config/load-config-profile :cli-assist)
          enable-tools (:enable-tools profile-config)]
      (is (some #{:clojure_edit_agent} enable-tools))
      (is (some #{:list_nrepl_ports} enable-tools))
      (is (some #{:clojure_eval} enable-tools))))

  (testing "cli-assist profile disables write-file-guard"
    (let [profile-config (config/load-config-profile :cli-assist)]
      (is (false? (:write-file-guard profile-config))))))
