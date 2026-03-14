(ns clojure-mcp.tools.form-edit.tool
  "Implementation of the s-expression editing tool using the tool-system multimethod approach.
   Provides the clojure_edit_replace_sexp tool for finding and replacing s-expressions."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.form-edit.pipeline :as pipeline]
   [clojure-mcp.utils.valid-paths :as valid-paths]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [rewrite-clj.parser :as p]
   [rewrite-clj.node :as n]))

;; Common validation functions

(defn validate-file-path
  "Validates that a file path is provided, within allowed directories, and is a Clojure file"
  [inputs nrepl-client-atom]
  (let [{:keys [file_path]} inputs
        nrepl-client @nrepl-client-atom]
    (when-not file_path
      (throw (ex-info "Missing required parameter: file_path"
                      {:inputs inputs})))
    (when-not (valid-paths/clojure-file? file_path)
      (throw (ex-info "File must have a Clojure extension (.clj, .cljs, .cljc, .cljd, .bb, .edn)"
                      {:file_path file_path})))
    ;; Use the utils/validate-path-with-client function to ensure path is valid
    (valid-paths/validate-path-with-client file_path nrepl-client)))

;; S-expression update tool

(defn create-update-sexp-tool
  "Creates the tool configuration for updating s-expressions in a file."
  [nrepl-client-atom]
  {:tool-type :clojure-update-sexp
   :nrepl-client-atom nrepl-client-atom
   :multi-op false})

(defmethod tool-system/tool-name :clojure-update-sexp [{:keys [multi-op]}]
  (if multi-op "clojure_update_sexp" "clojure_edit_replace_sexp"))

(defmethod tool-system/tool-description :clojure-update-sexp [{:keys [multi-op]}]
  (slurp
   (io/resource
    (if multi-op
      "clojure-mcp/tools/form_edit/clojure_update_sexp-description.md"
      "clojure-mcp/tools/form_edit/clojure_edit_replace_sexp-description.md"))))

(defmethod tool-system/tool-schema :clojure-update-sexp [{:keys [multi-op]}]
  {:type :object
   :properties
   (cond-> {:file_path {:type :string
                        :description "Path to the file to edit"}
            :match_form {:type :string
                         :description "The s-expression to find (include # for anonymous functions)"}
            :new_form {:type :string
                       :description "The s-expression to use for the operation"}
            :replace_all {:type :boolean
                          :description
                          (format "Whether to %s all occurrences (default: false)"
                                  (if multi-op "apply operation to" "replace"))}}
     multi-op
     (assoc :operation {:type :string
                        :enum ["replace" "insert_before" "insert_after"]
                        :description "The editing operation to perform"}))
   :required (cond-> [:file_path :match_form :new_form]
               multi-op (conj :operation))})

(defmethod tool-system/validate-inputs :clojure-update-sexp
  [{:keys [nrepl-client-atom multi-op]} inputs]
  (let [file-path (validate-file-path inputs nrepl-client-atom)
        {:keys [match_form new_form operation replace_all whitespace_sensitive dry_run]} inputs]
    (when-not match_form
      (throw (ex-info "Missing required parameter: match_form"
                      {:inputs inputs})))
    (when-not new_form
      (throw (ex-info "Missing required parameter: new_form"
                      {:inputs inputs})))
    (when (and multi-op
               (nil? operation))
      (throw (ex-info "Missing required parameter: operation"
                      {:inputs inputs})))
    (when (and multi-op
               operation
               (not (contains? #{"replace" "insert_before" "insert_after"} operation)))
      (throw (ex-info (str "Invalid operation: " operation
                           ". Supported operations: replace, insert_before, insert_after")
                      {:inputs inputs})))

    (when (str/blank? match_form)
      (throw (ex-info "Bad parameter: match-form can not be a blank string."
                      {:inputs inputs})))

    (when-not (str/blank? match_form)
      (try
        (let [parsed (p/parse-string-all match_form)]
          (when (zero? (count (n/child-sexprs parsed)))
            (throw (ex-info "match_form must contain at least one S-expression (not just comments or whitespace)"
                            {:inputs inputs}))))
        (catch Exception e
          (if (str/includes? (.getMessage e) "match_form must contain")
            (throw e)
            (throw (ex-info (str "Invalid Clojure code in match_form: " (.getMessage e))
                            {:inputs inputs}))))))

    (when-not (str/blank? new_form)
      (try
        (p/parse-string-all new_form)
        (catch Exception e
          (throw (ex-info (str "Invalid Clojure code in new_form: " (.getMessage e))
                          {:inputs inputs})))))
    {:file_path file-path
     :match_form match_form
     :new_form new_form
     :operation operation
     :replace_all (boolean (if (#{"insert_before" "insert_after"} operation)
                             false
                             (or replace_all false)))
     :whitespace_sensitive (boolean (or whitespace_sensitive false))
     :dry_run dry_run}))

(defmethod tool-system/execute-tool :clojure-update-sexp [{:keys [multi-op _nrepl-client-atom] :as tool} inputs]
  (let [{:keys [file_path match_form new_form operation replace_all whitespace_sensitive dry_run]} inputs
        operation-kw (if-not multi-op
                       :replace
                       (condp = operation
                         "replace" :replace
                         "insert_before" :insert-before
                         "insert_after" :insert-after))
        result (pipeline/sexp-edit-pipeline
                file_path match_form new_form operation-kw replace_all whitespace_sensitive dry_run tool)
        formatted-result (pipeline/format-result result)]
    formatted-result))

(defmethod tool-system/format-results :clojure-update-sexp [_ {:keys [error message diff new-source]}]
  (if error
    {:result [message]
     :error true}
    {:result [(or new-source diff)]
     :error false}))

;; Function to register the tool
(defn sexp-update-tool [nrepl-client-atom]
  (tool-system/registration-map (create-update-sexp-tool nrepl-client-atom)))
