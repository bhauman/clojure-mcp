(ns clojure-mcp.tools.project.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure-mcp.tools.project.core :refer [inspect-project-code
                                                    read-project-clj
                                                    parse-lein-config
                                                    extract-lein-project-info
                                                    extract-source-paths
                                                    extract-test-paths
                                                    determine-project-type
                                                    to-relative-path]])
  (:import [java.io File]
           [java.nio.file Files]))

(deftest lein-project-parsing-test
  (testing "parses real Leiningen project.clj file"
    (let [project-file (io/resource "clojure-mcp/test/projects/project.clj")
          project-content (-> project-file slurp read-string)
          lein-config (->> project-content
                           (drop 3)
                           (partition 2)
                           (map (fn [[k v]] [k v]))
                           (into {}))
          project-name (second project-content)
          version (nth project-content 2)]

      (is (= 'acme/widget-factory project-name))
      (is (= "2.3.0-SNAPSHOT" version))
      (is (= "A comprehensive widget manufacturing system" (:description lein-config)))
      (is (= "https://github.com/acme/widget-factory" (:url lein-config)))
      (is (map? (:license lein-config)))
      (is (= "Eclipse Public License" (get-in lein-config [:license :name])))
      (is (vector? (:dependencies lein-config)))
      (is (= 3 (count (:dependencies lein-config))))
      (is (= ["src/main/clj" "src/shared"] (:source-paths lein-config)))
      (is (= ["test/unit" "test/integration"] (:test-paths lein-config)))
      (is (= {:port 57802} (:repl-options lein-config)))
      (is (map? (:profiles lein-config))))))

(deftest lein-project-defaults-test
  (testing "handles default source and test paths for Leiningen projects"
    (let [minimal-project '(defproject my-app "1.0.0"
                             :dependencies [['org.clojure/clojure "1.11.1"]])

          lein-config (->> minimal-project
                           (drop 3)
                           (partition 2)
                           (map (fn [[k v]] [k v]))
                           (into {}))

          ;; Test default path logic
          source-paths (or (:source-paths lein-config) ["src"])
          test-paths (or (:test-paths lein-config) ["test"])]

      (is (= ["src"] source-paths))
      (is (= ["test"] test-paths))))

  (testing "respects explicit source and test paths"
    (let [custom-paths-project '(defproject my-app "1.0.0"
                                  :source-paths ["src/main/clj" "src/shared"]
                                  :test-paths ["test/unit" "test/integration"]
                                  :dependencies [['org.clojure/clojure "1.11.1"]])

          lein-config (->> custom-paths-project
                           (drop 3)
                           (partition 2)
                           (map (fn [[k v]] [k v]))
                           (into {}))

          source-paths (or (:source-paths lein-config) ["src"])
          test-paths (or (:test-paths lein-config) ["test"])]

      (is (= ["src/main/clj" "src/shared"] source-paths))
      (is (= ["test/unit" "test/integration"] test-paths)))))

(deftest deps-project-parsing-test
  (testing "parses real deps.edn file"
    (let [deps-file (io/resource "clojure-mcp/test/projects/deps.edn")
          deps-content (-> deps-file slurp edn/read-string)]

      (is (map? deps-content))
      (is (map? (:deps deps-content)))
      (is (= 3 (count (:deps deps-content))))
      (is (= ["src" "resources"] (:paths deps-content)))
      (is (map? (:aliases deps-content)))
      (is (contains? (:aliases deps-content) :test))
      (is (contains? (:aliases deps-content) :dev))
      (is (= ["test"] (get-in deps-content [:aliases :test :extra-paths])))
      (is (= ["dev"] (get-in deps-content [:aliases :dev :extra-paths]))))))

(deftest parse-lein-config-test
  (testing "parses valid project.clj configuration"
    (let [project-clj '(defproject my-app "1.0.0"
                         :description "Test app"
                         :dependencies [[org.clojure/clojure "1.11.1"]]
                         :source-paths ["src"]
                         :test-paths ["test"])
          config (parse-lein-config project-clj)]
      (is (= "Test app" (:description config)))
      (is (= 'org.clojure/clojure (first (first (:dependencies config)))))
      (is (= "1.11.1" (second (first (:dependencies config)))))
      (is (= ["src"] (:source-paths config)))
      (is (= ["test"] (:test-paths config)))))

  (testing "handles malformed project.clj"
    (is (nil? (parse-lein-config nil)))
    (is (nil? (parse-lein-config [])))
    (is (nil? (parse-lein-config '(defproject))))
    (is (nil? (parse-lein-config '(defproject my-app)))))

  (testing "handles odd number of config pairs"
    (let [project-clj '(defproject my-app "1.0.0" :description)
          config (parse-lein-config project-clj)]
      (is (nil? config)))))

(deftest extract-lein-project-info-test
  (testing "extracts basic project information"
    (let [project-clj '(defproject my-company/my-app "2.1.0"
                         :description "A great app"
                         :dependencies [[org.clojure/clojure "1.11.1"]]
                         :profiles {:dev {:dependencies [[midje "1.10.9"]]}})
          config (parse-lein-config project-clj)
          info (extract-lein-project-info project-clj config)]
      (is (= 'my-company/my-app (:name info)))
      (is (= "2.1.0" (:version info)))
      (is (= '[[org.clojure/clojure "1.11.1"]] (:dependencies info)))
      (is (= {:dev {:dependencies '[[midje "1.10.9"]]}} (:profiles info)))))

  (testing "handles minimal project.clj"
    (let [project-clj '(defproject basic "1.0.0")
          config (parse-lein-config project-clj)
          info (extract-lein-project-info project-clj config)]
      (is (= 'basic (:name info)))
      (is (= "1.0.0" (:version info)))
      (is (= [] (:dependencies info)))
      (is (= {} (:profiles info)))))

  (testing "handles malformed input"
    (let [info (extract-lein-project-info nil {})]
      (is (nil? info)))))

(deftest extract-source-paths-test
  (testing "extracts paths from deps.edn"
    (let [deps {:paths ["src" "resources"]}]
      (is (= ["src" "resources"] (extract-source-paths deps nil)))))

  (testing "extracts paths from lein config"
    (let [lein-config {:source-paths ["src/main/clj" "src/shared"]}]
      (is (= ["src/main/clj" "src/shared"] (extract-source-paths nil lein-config)))))

  (testing "defaults to src when no paths specified"
    (is (= ["src"] (extract-source-paths nil {})))
    (is (= ["src"] (extract-source-paths {} nil)))
    (is (= ["src"] (extract-source-paths nil nil))))

  (testing "handles invalid path configurations"
    (let [bad-deps {:paths "not-a-vector"}
          bad-lein {:source-paths 123}]
      (is (= ["src"] (extract-source-paths bad-deps nil)))
      (is (= ["src"] (extract-source-paths nil bad-lein))))))

(deftest extract-test-paths-test
  (testing "extracts test paths from deps.edn aliases"
    (let [deps {:aliases {:test {:extra-paths ["test" "test-integration"]}}}]
      (is (= ["test" "test-integration"] (extract-test-paths deps nil)))))

  (testing "extracts test paths from lein config"
    (let [lein-config {:test-paths ["test/unit" "test/integration"]}]
      (is (= ["test/unit" "test/integration"] (extract-test-paths nil lein-config)))))

  (testing "defaults to test when no paths specified"
    (is (= ["test"] (extract-test-paths nil {})))
    (is (= ["test"] (extract-test-paths {} nil)))
    (is (= ["test"] (extract-test-paths nil nil))))

  (testing "handles invalid test path configurations"
    (let [bad-deps {:aliases {:test {:extra-paths "not-a-vector"}}}
          bad-lein {:test-paths 123}]
      (is (= ["test"] (extract-test-paths bad-deps nil)))
      (is (= ["test"] (extract-test-paths nil bad-lein))))))

(deftest determine-project-type-test
  (testing "identifies project types correctly"
    (is (= "deps.edn" (determine-project-type {:paths ["src"]} nil)))
    (is (= "Leiningen" (determine-project-type nil '(defproject app "1.0.0"))))
    (is (= "deps.edn + Leiningen" (determine-project-type {:paths ["src"]} '(defproject app "1.0.0"))))
    (is (= "Unknown" (determine-project-type nil nil)))))

(deftest to-relative-path-test
  (testing "converts absolute paths to relative"
    (let [working-dir "/home/user/project"
          file-path "/home/user/project/src/main.clj"]
      (is (= "src/main.clj" (to-relative-path working-dir file-path)))))

  (testing "handles same directory"
    (let [working-dir "/home/user/project"
          file-path "/home/user/project"]
      (is (= "" (to-relative-path working-dir file-path)))))

  (testing "returns original path when relativization fails"
    (let [working-dir "/home/user/project"
          file-path "/completely/different/path/file.clj"]
      (is (string? (to-relative-path working-dir file-path))))))
