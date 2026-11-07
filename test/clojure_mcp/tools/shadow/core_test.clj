(ns clojure-mcp.tools.shadow.core-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure-mcp.config :as config]
            [clojure-mcp.tools.shadow.core :as core])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "shadow-core-test" (make-array FileAttribute 0))))

(defn- reads-as-one-form? [s]
  (with-open [r (java.io.PushbackReader. (java.io.StringReader. s))]
    (let [form (read r)]
      (and (some? form) (= ::eof (read r false ::eof))))))

(deftest invalid-build-arg-is-a-whitelist
  (testing "a build id is interpolated into an evaluated form, and the printer does not
            escape a keyword's name, so anything outside the keyword shape is refused"
    (is (string? (core/invalid-build-arg "a) (do :b")))
    (is (string? (core/invalid-build-arg "app/x")))
    (is (string? (core/invalid-build-arg "a b"))))
  (testing "real build ids pass, in all three spellings"
    (is (nil? (core/invalid-build-arg "app")))
    (is (nil? (core/invalid-build-arg ":dev")))
    (is (nil? (core/invalid-build-arg :dev))))
  (testing "absent, blank and colons-only mean no build, not an error"
    (is (nil? (core/invalid-build-arg nil)))
    (is (nil? (core/invalid-build-arg "")))
    (is (nil? (core/invalid-build-arg ":")))))

(deftest normalize-build-kw-spellings
  (is (= :app (core/normalize-build-kw "app")))
  (is (= :app (core/normalize-build-kw ":app")))
  (is (= :app (core/normalize-build-kw :app)))
  (is (nil? (core/normalize-build-kw "::")))
  (is (nil? (core/normalize-build-kw nil))))

(deftest forms-are-readable
  (doseq [form [(core/cljs-eval-form :dev "(+ 1 2)" nil)
                (core/cljs-eval-form nil "(js/alert \"x\")" 3)
                (core/build-status-form :dev)
                (core/build-status-form nil)
                (core/project-state-form)
                (core/list-ns-form :dev "^myapp")
                (core/list-ns-form nil nil)]]
    (is (reads-as-one-form? form) form)))

(deftest absent-build-resolves-in-the-form
  (testing "with no build given, the form picks the only active build at eval time"
    (is (string/includes? (core/cljs-eval-form nil "1" nil) "active-builds"))
    (is (string/includes? (core/list-ns-form nil nil) "active-builds"))))

(deftest resolve-port-order
  (let [dir (temp-dir)
        m {::config/config {:nrepl-user-dir dir}}]
    (testing "an explicit port wins"
      (is (= 1234 (core/resolve-port m 1234))))
    (testing "else the project's .shadow-cljs/nrepl.port"
      (let [f (io/file dir ".shadow-cljs" "nrepl.port")]
        (io/make-parents f)
        (spit f "4567")
        (is (= 4567 (core/resolve-port m nil)))))
    (testing "else an error that names list_nrepl_ports"
      (let [e (try (core/resolve-port {::config/config {:nrepl-user-dir (temp-dir)}} nil)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (string/includes? (ex-message e) "list_nrepl_ports"))))))
