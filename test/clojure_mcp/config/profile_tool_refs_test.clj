(ns clojure-mcp.config.profile-tool-refs-test
  "clojure_eval's description sends the agent to other tools by name. A profile
   that serves clojure_eval but not those tools points the agent at a tool it
   cannot call."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]
            [clojure-mcp.tools.eval.tool :as eval-tool]))

(defn- profile-names []
  (->> (file-seq (io/file (io/resource "clojure_mcp/configs")))
       (map #(.getName ^java.io.File %))
       (keep #(second (re-matches #"(.+)\.edn" %)))))

(deftest profiles-serve-the-tools-clojure-eval-points-to
  (let [description (:description (eval-tool/eval-code (atom {::config/config {}})))
        referenced (set (map keyword (re-seq #"\bcljs_[a-z_]+\b" description)))]
    (is (seq referenced) "the description is expected to name the cljs tools")
    (doseq [profile (profile-names)
            :let [m {::config/config (config/load-config-profile profile)}]
            :when (config/tool-id-enabled? m :clojure_eval)
            tool referenced]
      (testing (str "profile " profile)
        (is (config/tool-id-enabled? m tool) (str profile " serves clojure_eval but not " tool))))))
