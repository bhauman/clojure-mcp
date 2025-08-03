(ns clojure-mcp.tools.project.core-test
  "Unit tests for clojure-mcp.tools.project.core namespace.

  NOTE: This project appears to rely on clojure.test (standard library) for
  testing – that is the framework we use here."
  (:require
   [clojure.test :refer :all]
   [clojure-mcp.tools.project.core :as sut]
   [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers / Fakes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-working-dir "/tmp")        ; Use harmless fake paths for pure functions
(def allowed-dirs ["/tmp" "/opt"])

(defn fake-validate-path
  "A stub for vpaths/validate-path that simply returns the given path when valid.
  Any path NOT in allowed-dirs triggers an Exception (simulating the real impl)."
  [path working-dir allowed-dirs]
  (when (some #(or (= path %)
                   (.startsWith path (str % "/")))
              allowed-dirs)
    path))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; to-relative-path
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest to-relative-path-happy
  (testing "Relative path conversion succeeds"
    (let [wd "/home/user/project"
          fp "/home/user/project/src/core.clj"]
      (is (= "src/core.clj"
             (sut/to-relative-path wd fp))))))

(deftest to-relative-path-fallback
  (testing "Returns original path when relativize throws"
    ;; Intentionally pass impossible paths to force exception
    (let [wd "invalid:<>"
          fp "/abs/path/file.clj"]
      (is (= fp (sut/to-relative-path wd fp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; read-deps-edn / read-project-clj / read-bb-edn
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest read-deps-edn-nonexistent
  (testing "Gracefully returns nil when deps.edn does not exist"
    (is (nil? (sut/read-deps-edn "/path/does/not/exist")))))

(deftest read-project-clj-nonexistent
  (testing "Gracefully returns nil when project.clj does not exist"
    (is (nil? (sut/read-project-clj "/path/does/not/exist")))))

(deftest read-bb-edn-nonexistent
  (testing "Gracefully returns nil when bb.edn does not exist"
    (is (nil? (sut/read-bb-edn "/path/does/not/exist")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; parse-lein-config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-lein-config-happy
  (testing "Parses typical Lein vector successfully"
    (let [project-clj '(defproject sample "0.1.0"
                         :dependencies [[org.clojure/clojure "1.11.0"]]
                         :source-paths ["src" "lib"]
                         :test-paths ["test"])]
      (is (= {:dependencies [[org.clojure/clojure "1.11.0"]]
              :source-paths ["src" "lib"]
              :test-paths ["test"]}
             (sut/parse-lein-config project-clj)))))

  (testing "Returns empty map on malformed vector"
    (is (empty? (sut/parse-lein-config '(defproject only-two-elems))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; extract-lein-project-info
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest extract-lein-project-info-basic
  (testing "Extracts minimal project info"
    (let [pc '(defproject my-lib "1.2.3")
          cfg {}]
      (is (= {:name "my-lib"
              :version "1.2.3"
              :dependencies []
              :profiles {}}
             (sut/extract-lein-project-info pc cfg)))))

  (testing "Handles missing name/version gracefully"
    (is (= {:name "Unknown"
            :version "Unknown"
            :dependencies []
            :profiles {}}
           (sut/extract-lein-project-info '() {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; extract-source-paths / extract-test-paths
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest extract-source-paths-priority
  (testing "bb.edn paths override deps/lein"
    (let [bb {:paths ["bb-src"]}
          deps {:paths ["src"]}
          lein {:source-paths ["l-src"]}]
      (is (= ["bb-src"] (sut/extract-source-paths deps lein bb)))))

  (testing "deps.edn paths used when bb.edn absent"
    (let [deps {:paths ["src" "lib"]}]
      (is (= ["src" "lib"]
             (sut/extract-source-paths deps nil nil)))))

  (testing "Falls back to [\"src\"] on invalid input"
    (is (= ["src"] (sut/extract-source-paths {:paths :not-a-vec} nil nil)))))

(deftest extract-test-paths-priority
  (testing "deps.edn alias extra-paths preferred"
    (let [deps {:aliases {:test {:extra-paths ["unit" "integration"]}}}]
      (is (= ["unit" "integration"]
             (sut/extract-test-paths deps nil nil)))))

  (testing "Lein test paths used when deps data absent"
    (let [lein {:test-paths ["lein-test"]}]
      (is (= ["lein-test"]
             (sut/extract-test-paths nil lein nil)))))

  (testing "Falls back to [\"test\"] on invalid input"
    (is (= ["test"]
           (sut/extract-test-paths {:aliases {:test {:extra-paths :oops}}} nil nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; determine-project-type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest determine-project-type-matrix
  (testing "Determines all combinations correctly"
    (let [deps {:deps {}}
          pclj '(defproject x "0.0.1")
          bb {:paths []}]
      (is (= "deps.edn + Leiningen + Babashka"
             (sut/determine-project-type deps pclj bb)))
      (is (= "deps.edn + Babashka"
             (sut/determine-project-type deps nil bb)))
      (is (= "Leiningen + Babashka"
             (sut/determine-project-type nil pclj bb)))
      (is (= "Babashka"
             (sut/determine-project-type nil nil bb)))
      (is (= "deps.edn + Leiningen"
             (sut/determine-project-type deps pclj nil)))
      (is (= "deps.edn"
             (sut/determine-project-type deps nil nil)))
      (is (= "Leiningen"
             (sut/determine-project-type nil pclj nil)))
      (is (= "Unknown"
             (sut/determine-project-type nil nil nil))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; format-describe
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest format-describe-bb
  (testing "Returns only babashka key when present"
    (is (= {:babashka "1.3.181"}
           (sut/format-describe {:versions {:babashka "1.3.181"
                                             :clj-kondo {:version-string "2023.04"}}}))))

  (testing "Transforms nested map into k=>version map"
    (is (= {:clj-kondo "2023.04" :foo "1.2.3"}
           (sut/format-describe {:versions {:foo {:version-string "1.2.3"}
                                            :clj-kondo {:version-string "2023.04"}}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration-style test for inspect-project caching behaviour
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest inspect-project-caches-result
  (testing "Second call should hit cache to avoid recomputation"
    ;; We'll create an atom with a fake nREPL client that provides minimal API.
    (let [calls (atom 0)
          fake-client
          {:describe (fn [_] (swap! calls inc)
                       {:versions {:clojure {:version-string "1.11.1"}}})
           ;; config namespace fakes
           ;; keep simple deterministic returns
           }
          ;; Reify config functions via partial redefinitions
          _ (with-redefs [clojure-mcp.config/get-allowed-directories (constantly allowed-dirs)
                          clojure-mcp.config/get-nrepl-user-dir (constantly test-working-dir)
                          clojure-mcp.config/get-nrepl-env-type (constantly :clj)
                          clojure-mcp.nrepl/describe (fn [_] {:versions {:clojure {:version-string "1.11.1"}}})]
              (let [client-atom (atom fake-client)]
                (sut/inspect-project client-atom) ; first run populates cache
                (sut/inspect-project client-atom) ; second should reuse
                (is (= 1 @calls)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Run all tests with verbosity when file is evaluated directly (e.g., via REPL)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (run-tests))
