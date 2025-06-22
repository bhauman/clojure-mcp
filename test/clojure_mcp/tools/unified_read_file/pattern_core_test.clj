(ns clojure-mcp.tools.unified-read-file.pattern-core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure-mcp.tools.unified-read-file.pattern-core :as collapsed]))

;; Test data
(def basic-source
  "(ns test.example)

(defn simple-function
  \"A simple function\"
  [x y]
  (+ x y))

(defn complex-function
  ([x] (complex-function x 1))
  ([x y] (* x y)))

(def configuration
  {:port 8080
   :host \"localhost\"})

(defmacro with-timing
  [& body]
  `(time (do ~@body)))")

(def defmethod-source
  "(ns test.methods)

(defmulti process :type)

(defmethod process :type-a
  [m]
  (assoc m :processed true))

(defmethod process :type-b
  [m]
  (update m :count inc))

(defmethod area :rectangle
  [{:keys [width height]}]
  (* width height))

(defmethod tool-system/validate :my-tool
  [_ inputs]
  {:valid? true})")

(def reader-conditional-source
  "(ns test.cljc)

(defn common-fn [x] (inc x))

#?(:clj
   (defn server-fn
     \"Server-side function\"
     []
     :server))

#?(:cljs
   (defn client-fn
     \"Client-side function\"
     []
     :client))

#?@(:clj
    [(def server-port 8080)
     (defn start-server []
       (println \"Starting server\"))]
    :cljs
    [(def client-endpoint \"http://localhost:8080\")
     (defn connect-to-server []
       (println \"Connecting\"))])")

(def spec-source
  "(ns test.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::email string?)
(s/def ::age (s/and int? #(>= % 0)))
(s/def ::user
  (s/keys :req-un [::email]
          :opt-un [::age]))

(s/def :domain.user/id uuid?)
(s/def :domain.user/name string?)

(s/fdef process-user
  :args (s/cat :user ::user)
  :ret map?)

(defn process-user [user]
  (assoc user :processed true))")

(def metadata-source
  "(ns test.metadata)

(defn ^:deprecated old-function
  \"This function is deprecated\"
  [x]
  (* x 2))

(defn ^{:author \"John Doe\"
        :version \"1.0\"} 
  documented-fn
  [x y]
  (+ x y))

(defn ^:private helper-fn [] :helper)

(def ^:const ^{:doc \"The answer\"} answer 42)

(defmethod ^:deprecated process :old-type
  [x]
  {:old x})")

;; Tests

(deftest test-basic-collapsed-view
  (testing "Basic collapsed view without patterns"
    (let [result (collapsed/generate-collapsed-view* basic-source nil nil)]
      (is (map? result))
      (is (string? (:view result)))
      (is (= 5 (get-in result [:pattern-info :total-forms])))
      (is (= 0 (get-in result [:pattern-info :expanded-forms])))
      (is (= 5 (get-in result [:pattern-info :collapsed-forms])))

      ;; Check collapsed representations
      (is (re-find #"\(defn simple-function \[x y\] \.\.\.\)" (:view result)))
      (is (re-find #"\(defn complex-function \[\.\.\.\] \.\.\.\)" (:view result)))
      (is (re-find #"\(def configuration \.\.\.\)" (:view result)))
      (is (re-find #"\(defmacro with-timing \[& body\] \.\.\.\)" (:view result)))

      ;; Namespace should be shown in full
      (is (re-find #"\(ns test\.example\)" (:view result))))))

(deftest test-name-pattern-matching
  (testing "Pattern matching on form names"
    (let [result (collapsed/generate-collapsed-view* basic-source "simple" nil)]
      (is (= 1 (get-in result [:pattern-info :expanded-forms])))
      (is (= 4 (get-in result [:pattern-info :collapsed-forms])))
      (is (re-find #"\"A simple function\"" (:view result)))
      (is (re-find #"\(defn complex-function \[\.\.\.\] \.\.\.\)" (:view result)))))

  (testing "Multiple name matches"
    (let [result (collapsed/generate-collapsed-view* basic-source "function" nil)]
      (is (= 2 (get-in result [:pattern-info :expanded-forms])))
      (is (= 3 (get-in result [:pattern-info :collapsed-forms]))))))

(deftest test-content-pattern-matching
  (testing "Pattern matching on form content"
    (let [result (collapsed/generate-collapsed-view* basic-source nil "time")]
      (is (= 1 (get-in result [:pattern-info :expanded-forms])))
      (is (re-find #"`\(time" (:view result)))))

  (testing "Content pattern with special characters"
    (let [result (collapsed/generate-collapsed-view* basic-source nil "\\+ x y")]
      (is (= 1 (get-in result [:pattern-info :expanded-forms]))))))

(deftest test-combined-patterns
  (testing "Both name and content patterns"
    (let [result (collapsed/generate-collapsed-view* basic-source "simple-function" "\\+")]
      (is (= 1 (get-in result [:pattern-info :expanded-forms])))
      (is (re-find #"simple-function" (:view result)))
      (is (not (re-find #"complex-function\s+\[x\]" (:view result)))))))

(deftest test-defmethod-forms
  (testing "Defmethod collapsed view"
    (let [result (collapsed/generate-collapsed-view* defmethod-source nil nil)]
      (is (re-find #"\(defmethod process :type-a \[m\] \.\.\.\)" (:view result)))
      (is (re-find #"\(defmethod area :rectangle \[\{:keys" (:view result)))))

  (testing "Defmethod pattern matching by dispatch value"
    (let [result (collapsed/generate-collapsed-view* defmethod-source "process :type-a" nil)]
      (is (= 1 (get-in result [:pattern-info :expanded-forms])))
      (is (re-find #"assoc m :processed true" (:view result)))))

  (testing "Namespaced defmethod"
    (let [result (collapsed/generate-collapsed-view* defmethod-source "tool-system/validate" nil)]
      (is (= 1 (get-in result [:pattern-info :expanded-forms]))))))

(deftest test-reader-conditionals
  (testing "Reader conditionals collapsed view"
    (let [result (collapsed/generate-collapsed-view* reader-conditional-source nil nil)]
      (is (re-find #"#\?\(:clj\n   \(defn server-fn \[\] \.\.\.\)\)" (:view result)))
      (is (re-find #"#\?\(:cljs\n   \(defn client-fn \[\] \.\.\.\)\)" (:view result)))
      (is (re-find #"#\?\(:clj\n   \(def server-port \.\.\.\)\)" (:view result)))))

  (testing "Expanding reader conditional forms"
    (let [result (collapsed/generate-collapsed-view* reader-conditional-source "server-fn|start-server" nil)]
      (is (= 2 (get-in result [:pattern-info :expanded-forms])))
      (is (re-find #"\"Server-side function\"" (:view result)))
      (is (re-find #"println \"Starting server\"" (:view result))))))

(deftest test-spec-forms
  (testing "Spec forms with keywords"
    (let [result (collapsed/generate-collapsed-view* spec-source nil nil)]
      ;; Auto-resolved keywords should show as ::keyword
      (is (re-find #"\(s/def ::email \.\.\.\)" (:view result)))
      (is (re-find #"\(s/def ::age \.\.\.\)" (:view result)))
      ;; Qualified keywords should show as :ns/name
      (is (re-find #"\(s/def :domain\.user/id \.\.\.\)" (:view result)))
      ;; Function specs
      (is (re-find #"\(s/fdef process-user \.\.\.\)" (:view result)))))

  (testing "Spec pattern matching"
    (let [result (collapsed/generate-collapsed-view* spec-source "::age" nil)]
      (is (= 1 (get-in result [:pattern-info :expanded-forms])))
      (is (re-find #"s/and int\?" (:view result)))))

  (testing "Qualified keyword pattern matching"
    (let [result (collapsed/generate-collapsed-view* spec-source ":domain\\.user/id" nil)]
      (is (= 1 (get-in result [:pattern-info :expanded-forms])))
      (is (re-find #"uuid\\?" (:view result))))))

(deftest test-metadata-handling
  (testing "Functions with metadata"
    (let [result (collapsed/generate-collapsed-view* metadata-source nil nil)]
      ;; Metadata doesn't interfere with collapsed view
      (is (re-find #"\(defn old-function \[x\] \.\.\.\)" (:view result)))
      (is (re-find #"\(defn documented-fn \[x y\] \.\.\.\)" (:view result)))
      (is (re-find #"\(def answer \.\.\.\)" (:view result)))))

  (testing "Expanding functions with metadata preserves metadata"
    (let [result (collapsed/generate-collapsed-view* metadata-source "old-function" nil)]
      (is (re-find #"\^:deprecated" (:view result)))
      (is (re-find #"\"This function is deprecated\"" (:view result)))))

  (testing "Defmethod with metadata"
    (let [result (collapsed/generate-collapsed-view* metadata-source "process :old-type" nil)]
      (is (= 1 (get-in result [:pattern-info :expanded-forms])))
      (is (re-find #"\^:deprecated" (:view result))))))

(deftest test-edge-cases
  (testing "Empty source"
    (let [result (collapsed/generate-collapsed-view* "" nil nil)]
      (is (= "" (:view result)))
      (is (= 0 (get-in result [:pattern-info :total-forms])))))

  (testing "Source with only namespace"
    (let [result (collapsed/generate-collapsed-view* "(ns test.only)" nil nil)]
      (is (= "(ns test.only)" (:view result)))
      (is (= 1 (get-in result [:pattern-info :total-forms])))))

  (testing "Invalid regex patterns"
    (is (thrown-with-msg? Exception #"Invalid name pattern regex"
                          (collapsed/generate-collapsed-view* basic-source "[invalid" nil)))
    (is (thrown-with-msg? Exception #"Invalid content pattern regex"
                          (collapsed/generate-collapsed-view* basic-source nil "[invalid")))))

(deftest test-form-collection
  (testing "collect-top-level-forms* extracts correct metadata"
    (let [forms (collapsed/collect-top-level-forms* basic-source false)]
      (is (= 5 (count forms)))
      (is (= ["test.example" "simple-function" "complex-function" "configuration" "with-timing"]
             (map :name forms)))
      (is (= ["ns" "defn" "defn" "def" "defmacro"]
             (map :type forms)))))

  (testing "Forms in reader conditionals have platform info"
    (let [forms (collapsed/collect-top-level-forms* reader-conditional-source false)]
      (is (some #(and (= "server-fn" (:name %))
                      (= :clj (:platform %))) forms))
      (is (some #(and (= "client-fn" (:name %))
                      (= :cljs (:platform %))) forms)))))

(deftest test-pattern-based-file-view
  (testing "generate-pattern-based-file-view* returns matches only"
    (let [result (collapsed/generate-pattern-based-file-view* basic-source "function" nil)]
      (is (= 2 (count (:matches result))))
      (is (= #{"simple-function" "complex-function"}
             (set (map :name (:matches result)))))
      (is (= 2 (get-in result [:pattern-info :match-count])))))

  (testing "Pattern matching with platform info"
    (let [result (collapsed/generate-pattern-based-file-view* reader-conditional-source "^server-" nil)]
      (is (= 2 (count (:matches result))))
      (is (every? #(= :clj (:platform %)) (:matches result))))))

(deftest test-special-forms-and-edge-cases
  (testing "Protocol and record forms"
    (let [source "(ns test.proto)
                  
                  (defprotocol MyProtocol
                    \"A protocol\"
                    (my-method [this] [this x]))
                  
                  (defrecord MyRecord [a b]
                    MyProtocol
                    (my-method [this] :one-arg)
                    (my-method [this x] :two-args))"]
      (let [result (collapsed/generate-collapsed-view* source nil nil)]
        (is (re-find #"\(defprotocol MyProtocol \.\.\.\)" (:view result)))
        (is (re-find #"\(defrecord MyRecord \.\.\.\)" (:view result))))))

  (testing "Multiline strings in forms"
    (let [source "(ns test.multi)
                  
                  (def long-string
                    \"This is a very long
                     multiline string that
                     spans several lines\")
                  
                  (defn process-text
                    \"Process multiline text\"
                    [text]
                    (str/replace text #\"\\n\" \" \"))"]
      (let [result (collapsed/generate-collapsed-view* source "long-string" nil)]
        (is (= 1 (get-in result [:pattern-info :expanded-forms])))
        (is (re-find #"multiline string" (:view result))))))

  (testing "Complex nested forms"
    (let [source "(ns test.nested)
                  
                  (defn deeply-nested
                    [x]
                    (let [a (+ x 1)
                          b (* a 2)]
                      (if (> b 10)
                        (do
                          (println \"Large value:\" b)
                          (reduce + (map #(* % %) (range b))))
                        b)))"]
      (let [result (collapsed/generate-collapsed-view* source nil "reduce")]
        (is (= 1 (get-in result [:pattern-info :expanded-forms])))
        (is (re-find #"reduce \+" (:view result))))))

  (testing "Anonymous functions and special characters"
    (let [source "(ns test.anon)
                  
                  (def handlers
                    {:click #(println \"Clicked:\" %)
                     :hover (fn [e] (println \"Hovered:\" e))})
                  
                  (defn process->result
                    \"Process with special chars in name\"
                    [data]
                    (-> data
                        (update :value inc)
                        (assoc :processed? true)))"]
      (let [result (collapsed/generate-collapsed-view* source "process->result" nil)]
        (is (= 1 (get-in result [:pattern-info :expanded-forms])))
        (is (re-find #"Process with special chars" (:view result)))))))

;; Run tests with: clojure -X:test :nses '[clojure-mcp.tools.unified-read-file.pattern-core-enhanced-with-collapsed-test]