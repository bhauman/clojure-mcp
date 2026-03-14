(ns clojure-mcp.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]
            [clojure-mcp.core]))

(def ^:private apply-cli-config-overrides
  @#'clojure-mcp.core/apply-cli-config-overrides)

(defn- make-client [config]
  {::config/config config})

(deftest enable-tools-override-test
  (testing ":enable-tools from opts replaces config value"
    (let [client (make-client {:enable-tools [:foo :bar]})
          result (apply-cli-config-overrides client {:enable-tools [:baz]})]
      (is (= [:baz] (get-in result [::config/config :enable-tools])))))

  (testing ":disable-tools from opts replaces config value"
    (let [client (make-client {:disable-tools [:foo]})
          result (apply-cli-config-overrides client {:disable-tools [:bar :baz]})]
      (is (= [:bar :baz] (get-in result [::config/config :disable-tools]))))))

(deftest remove-tools-test
  (testing ":remove-tools adds to :disable-tools"
    (let [client (make-client {:disable-tools [:foo]})
          result (apply-cli-config-overrides client {:remove-tools [:bar]})]
      (is (= [:foo :bar] (get-in result [::config/config :disable-tools])))))

  (testing ":remove-tools removes from :enable-tools"
    (let [client (make-client {:enable-tools [:foo :bar :baz]})
          result (apply-cli-config-overrides client {:remove-tools [:bar]})]
      (is (= [:foo :baz] (get-in result [::config/config :enable-tools])))))

  (testing ":remove-tools with no existing :disable-tools"
    (let [client (make-client {})
          result (apply-cli-config-overrides client {:remove-tools [:foo]})]
      (is (= [:foo] (get-in result [::config/config :disable-tools])))))

  (testing ":remove-tools does not create :enable-tools when nil"
    (let [client (make-client {})
          result (apply-cli-config-overrides client {:remove-tools [:foo]})]
      (is (nil? (get-in result [::config/config :enable-tools]))))))

(deftest add-tools-test
  (testing ":add-tools removes from :disable-tools"
    (let [client (make-client {:disable-tools [:foo :bar :baz]})
          result (apply-cli-config-overrides client {:add-tools [:bar]})]
      (is (= [:foo :baz] (get-in result [::config/config :disable-tools])))))

  (testing ":add-tools adds to :enable-tools when set"
    (let [client (make-client {:enable-tools [:foo]})
          result (apply-cli-config-overrides client {:add-tools [:bar]})]
      (is (= [:foo :bar] (get-in result [::config/config :enable-tools])))))

  (testing ":add-tools does not create :enable-tools when nil"
    (let [client (make-client {})
          result (apply-cli-config-overrides client {:add-tools [:bar]})]
      (is (nil? (get-in result [::config/config :enable-tools]))))))

(deftest add-tools-wins-over-remove-tools-test
  (testing ":add-tools wins over :remove-tools on overlap"
    (let [client (make-client {:enable-tools [:foo]
                               :disable-tools [:baz]})
          result (apply-cli-config-overrides client {:remove-tools [:bar :qux]
                                                     :add-tools [:bar]})]
      ;; :bar should NOT be in :disable-tools (add-tools removed it)
      ;; :qux should be in :disable-tools (remove-tools added it, add-tools didn't touch it)
      (is (not (some #{:bar} (get-in result [::config/config :disable-tools]))))
      (is (some #{:qux} (get-in result [::config/config :disable-tools])))
      ;; :bar should be in :enable-tools (add-tools added it back)
      (is (some #{:bar} (get-in result [::config/config :enable-tools]))))))

(deftest combination-with-config-profile-test
  (testing "cli-assist profile with :add-tools re-enables a tool"
    ;; Simulate cli-assist having a limited enable-tools list
    (let [client (make-client {:enable-tools [:clojure_eval :read_file]
                               :disable-tools []})
          result (apply-cli-config-overrides client {:add-tools [:my_custom_agent]})]
      (is (some #{:my_custom_agent} (get-in result [::config/config :enable-tools])))))

  (testing "cli-assist profile with :remove-tools disables a tool"
    (let [client (make-client {:enable-tools [:clojure_eval :read_file :bash]
                               :disable-tools []})
          result (apply-cli-config-overrides client {:remove-tools [:clojure_eval]})]
      (is (not (some #{:clojure_eval} (get-in result [::config/config :enable-tools]))))
      (is (some #{:clojure_eval} (get-in result [::config/config :disable-tools]))))))

(deftest no-ops-test
  (testing "no opts produces no changes"
    (let [client (make-client {:enable-tools [:foo] :disable-tools [:bar]})]
      (is (= client (apply-cli-config-overrides client {})))))

  (testing "string tool names are converted to keywords"
    (let [client (make-client {:disable-tools [:foo]})
          result (apply-cli-config-overrides client {:remove-tools ["bar"]})]
      (is (= [:foo :bar] (get-in result [::config/config :disable-tools]))))))
