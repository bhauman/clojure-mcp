(ns clojure-mcp.tools.scratch-pad.tool-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.scratch-pad.tool :as sut]))

(defn create-mock-nrepl-client-atom
  "Creates a mock nrepl-client-atom for testing."
  []
  (atom {}))

(deftest tool-metadata-test
  (let [tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom (create-mock-nrepl-client-atom)
                     :working-directory "/tmp/test"}]

    (testing "tool-name returns the correct name"
      (is (= "scratch_pad" (tool-system/tool-name tool-config))))

    (testing "tool-description returns a non-empty string"
      (let [description (tool-system/tool-description tool-config)]
        (is (string? description))
        (is (not (empty? description)))
        (is (re-find #"scratch pad" description))))

    (testing "tool-schema returns a valid schema"
      (let [schema (tool-system/tool-schema tool-config)]
        (is (map? schema))
        (is (= "object" (:type schema)))
        (is (map? (:properties schema)))
        (is (contains? (:properties schema) "op"))
        (is (contains? (:properties schema) "path"))
        (is (contains? (:properties schema) "value"))
        (is (contains? (:properties schema) "explanation"))

        (is (contains? (:properties schema) "depth"))
        (is (= ["op" "explanation"] (:required schema)))))))

(deftest validate-inputs-test
  (let [tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom (create-mock-nrepl-client-atom)
                     :working-directory "/tmp/test"}]

    (testing "validate-inputs for set_path operation"
      (testing "valid inputs"
        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "set_path"
                       :path ["key"]
                       :value "test"
                       :explanation "test"})]
          (is (= ["key"] (:path result)))
          (is (vector? (:path result)))))

      (testing "missing path throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter for set_path: path"
             (tool-system/validate-inputs
              tool-config
              {:op "set_path"
               :value "test"
               :explanation "test"}))))

      (testing "missing value throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter for set_path: value"
             (tool-system/validate-inputs
              tool-config
              {:op "set_path"
               :path ["key"]
               :explanation "test"})))))

    (testing "validate-inputs for get_path operation"
      (testing "valid inputs"
        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "get_path"
                       :path ["key"]
                       :explanation "test"})]
          (is (= ["key"] (:path result)))))

      (testing "missing path throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter for get_path: path"
             (tool-system/validate-inputs
              tool-config
              {:op "get_path"
               :explanation "test"})))))

    (testing "validate-inputs for delete_path operation"
      (testing "valid inputs"
        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "delete_path"
                       :path ["key"]
                       :explanation "test"})]
          (is (= ["key"] (:path result)))))

      (testing "missing path throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter for delete_path: path"
             (tool-system/validate-inputs
              tool-config
              {:op "delete_path"
               :explanation "test"})))))

    (testing "validate-inputs for inspect operation"
      (testing "valid inputs without depth"
        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "inspect"
                       :explanation "test"})]
          (is (= 5 (:depth result)))))

      (testing "valid inputs with depth"
        (let [result (tool-system/validate-inputs
                      tool-config
                      {:op "inspect"
                       :depth 3
                       :explanation "test"})]
          (is (= 3 (:depth result)))))

      (testing "invalid depth throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Depth must be a positive integer greater than 0"
             (tool-system/validate-inputs
              tool-config
              {:op "inspect"
               :depth 0
               :explanation "test"})))

        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Depth must be a positive integer greater than 0"
             (tool-system/validate-inputs
              tool-config
              {:op "inspect"
               :depth -1
               :explanation "test"})))

        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Depth must be a positive integer greater than 0"
             (tool-system/validate-inputs
              tool-config
              {:op "inspect"
               :depth 3.5
               :explanation "test"})))))

    (testing "common validation"
      (testing "missing op throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter: op"
             (tool-system/validate-inputs
              tool-config
              {:path ["key"]
               :explanation "test"}))))

      (testing "missing explanation throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Missing required parameter: explanation"
             (tool-system/validate-inputs
              tool-config
              {:op "get_path"
               :path ["key"]}))))

      (testing "invalid operation throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Invalid operation"
             (tool-system/validate-inputs
              tool-config
              {:op "invalid_op"
               :explanation "test"}))))

      (testing "empty path throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Path must have at least one element"
             (tool-system/validate-inputs
              tool-config
              {:op "get_path"
               :path []
               :explanation "test"}))))

      (testing "invalid path element type throws"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Path elements must be strings or numbers"
             (tool-system/validate-inputs
              tool-config
              {:op "get_path"
               :path ["valid" :invalid]
               :explanation "test"})))))))

(deftest execute-tool-test
  (let [nrepl-client-atom (create-mock-nrepl-client-atom)
        tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom nrepl-client-atom
                     :working-directory "/tmp/test"}]

    (testing "set_path operation"
      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "set_path"
                     :path ["test" "key"]
                     :value "test value"
                     :explanation "test"})]
        (is (= false (:error result)))
        (is (= ["test" "key"] (get-in result [:result :stored-at])))
        (is (= "test value" (get-in result [:result :value])))
        (is (string? (get-in result [:result :pretty-value])))
        ;; Check data was actually stored
        (is (= "test value" (get-in @nrepl-client-atom [::sut/scratch-pad "test" "key"])))))

    (testing "get_path operation"
      ;; First store some data
      (swap! nrepl-client-atom assoc ::sut/scratch-pad {"test" {"key" "stored value"}})

      (testing "existing path"
        (let [result (tool-system/execute-tool
                      tool-config
                      {:op "get_path"
                       :path ["test" "key"]
                       :explanation "test"})]
          (is (= false (:error result)))
          (is (= ["test" "key"] (get-in result [:result :path])))
          (is (= "stored value" (get-in result [:result :value])))
          (is (= true (get-in result [:result :found])))
          (is (string? (get-in result [:result :pretty-value])))))

      (testing "non-existent path"
        (let [result (tool-system/execute-tool
                      tool-config
                      {:op "get_path"
                       :path ["does" "not" "exist"]
                       :explanation "test"})]
          (is (= false (:error result)))
          (is (= ["does" "not" "exist"] (get-in result [:result :path])))
          (is (nil? (get-in result [:result :value])))
          (is (= false (get-in result [:result :found])))
          (is (nil? (get-in result [:result :pretty-value]))))))

    (testing "delete_path operation"
      ;; Store data to delete
      (swap! nrepl-client-atom assoc ::sut/scratch-pad {"test" {"key" "value" "other" "data"}})

      (let [result (tool-system/execute-tool
                    tool-config
                    {:op "delete_path"
                     :path ["test" "key"]
                     :explanation "test"})]
        (is (= false (:error result)))
        (is (= ["test" "key"] (get-in result [:result :removed-from])))
        ;; Check data was actually removed
        (is (nil? (get-in @nrepl-client-atom [::sut/scratch-pad "test" "key"])))
        ;; But other data remains
        (is (= "data" (get-in @nrepl-client-atom [::sut/scratch-pad "test" "other"])))))

    (testing "inspect operation"
      ;; Set up nested data
      (swap! nrepl-client-atom assoc ::sut/scratch-pad
             {"level1" {"level2" {"level3" {"level4" "deep value"}}}})

      (testing "with default depth"
        (let [result (tool-system/execute-tool
                      tool-config
                      {:op "inspect"
                       :explanation "test"
                       :depth 5})]
          (is (= false (:error result)))
          (is (string? (get-in result [:result :tree])))
          (is (re-find #"level1" (get-in result [:result :tree])))))

      (testing "with custom depth"
        (let [result (tool-system/execute-tool
                      tool-config
                      {:op "inspect"
                       :explanation "test"
                       :depth 2})]
          (is (= false (:error result)))
          (is (string? (get-in result [:result :tree])))
          (is (re-find #"\.\.\." (get-in result [:result :tree]))))))))

(deftest format-results-test
  (let [tool-config {:tool-type :scratch-pad
                     :nrepl-client-atom (create-mock-nrepl-client-atom)
                     :working-directory "/tmp/test"}]

    (testing "format-results for set_path"
      (let [result (tool-system/format-results
                    tool-config
                    {:error false
                     :result {:stored-at ["test"]
                              :value "value"
                              :pretty-value "\"value\"\n"
                              :parent-value {"test" "value"}}
                     :explanation "test explanation"})]
        (is (= false (:error result)))
        (is (vector? (:result result)))
        (is (= 1 (count (:result result))))
        (is (string? (first (:result result))))))

    (testing "format-results for get_path"
      (testing "when value found"
        (let [result (tool-system/format-results
                      tool-config
                      {:error false
                       :result {:path ["test"]
                                :value "value"
                                :pretty-value "\"value\"\n"
                                :found true}
                       :explanation "test explanation"})]
          (is (= false (:error result)))
          (is (= ["\"value\"\n"] (:result result)))))

      (testing "when value not found"
        (let [result (tool-system/format-results
                      tool-config
                      {:error false
                       :result {:path ["test"]
                                :value nil
                                :found false}
                       :explanation "test explanation"})]
          (is (= false (:error result)))
          (is (= ["nil"] (:result result))))))

    (testing "format-results for delete_path"
      (let [result (tool-system/format-results
                    tool-config
                    {:error false
                     :result {:removed-from ["test"]}
                     :explanation "test explanation"})]
        (is (= false (:error result)))
        (is (re-find #"Removed value at path" (first (:result result))))))

    (testing "format-results for inspect"
      (let [result (tool-system/format-results
                    tool-config
                    {:error false
                     :result {:tree "{\"test\" \"data\"}\n"}
                     :explanation "test explanation"})]
        (is (= false (:error result)))
        (is (= ["{\"test\" \"data\"}\n"] (:result result)))))

    (testing "format-results for error"
      (let [result (tool-system/format-results
                    tool-config
                    {:error true
                     :result "Error message"})]
        (is (= true (:error result)))
        (is (= ["Error message"] (:result result)))))))

(deftest scratch-pad-state-test
  (testing "scratch pad isolation between tests"
    (let [atom1 (create-mock-nrepl-client-atom)
          atom2 (create-mock-nrepl-client-atom)
          tool1 {:tool-type :scratch-pad :nrepl-client-atom atom1 :working-directory "/tmp/test1"}
          tool2 {:tool-type :scratch-pad :nrepl-client-atom atom2 :working-directory "/tmp/test2"}]

      ;; Store data in first tool
      (tool-system/execute-tool
       tool1
       {:op "set_path"
        :path ["isolated"]
        :value "tool1 data"
        :explanation "test"})

      ;; Store different data in second tool
      (tool-system/execute-tool
       tool2
       {:op "set_path"
        :path ["isolated"]
        :value "tool2 data"
        :explanation "test"})

      ;; Verify isolation
      (let [result1 (tool-system/execute-tool
                     tool1
                     {:op "get_path"
                      :path ["isolated"]
                      :explanation "test"})
            result2 (tool-system/execute-tool
                     tool2
                     {:op "get_path"
                      :path ["isolated"]
                      :explanation "test"})]
        (is (= "tool1 data" (get-in result1 [:result :value])))
        (is (= "tool2 data" (get-in result2 [:result :value])))))))
