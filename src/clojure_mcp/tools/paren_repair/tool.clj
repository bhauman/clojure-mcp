(ns clojure-mcp.tools.paren-repair.tool
  "MCP tool for repairing delimiter errors in Clojure files using parinfer."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.paren-repair.core :as core]
   [clojure-mcp.utils.valid-paths :as valid-paths]))

(defn create-paren-repair-tool
  "Creates the paren-repair tool configuration.

   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  {:tool-type :paren-repair
   :nrepl-client-atom nrepl-client-atom})

(defmethod tool-system/tool-name :paren-repair [_]
  "paren_repair")

(defmethod tool-system/tool-description :paren-repair [_]
  "Fix delimiter errors (unbalanced parentheses, brackets, braces) in a Clojure file using parinfer.

Use this tool when:
- A file has unbalanced delimiters causing parse errors
- You need to repair a file after an errant edit
- The file won't compile due to unbalanced parens/brackets

Returns a status message and diff showing what changed.")

(defmethod tool-system/tool-schema :paren-repair [_]
  {:type :object
   :properties {:file_path {:type :string
                            :description "Absolute path to the Clojure file to repair (.clj, .cljs, .cljc, .edn)"}}
   :required [:file_path]})

(defmethod tool-system/validate-inputs :paren-repair [{:keys [nrepl-client-atom]} inputs]
  (let [file-path (:file_path inputs)
        nrepl-client @nrepl-client-atom]
    (when (or (nil? file-path) (empty? file-path))
      (throw (ex-info "file_path is required" {:inputs inputs})))
    ;; Validate path using the utility function
    (let [validated-path (valid-paths/validate-path-with-client file-path nrepl-client)]
      {:file-path validated-path
       :nrepl-client-map nrepl-client})))

(defmethod tool-system/execute-tool :paren-repair [_ {:keys [file-path nrepl-client-map]}]
  (core/repair-file! nrepl-client-map file-path))

(defmethod tool-system/format-results :paren-repair [_ result]
  (let [{:keys [success file-path message diff]} result
        output (cond-> (str "File: " file-path "\n"
                            "Status: " message)
                 diff (str "\n\n" diff))]
    {:result [output]
     :error (not success)}))

;; Backward compatibility function that returns the registration map
(defn paren-repair-tool
  "Returns the registration map for the paren-repair tool.

   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-paren-repair-tool nrepl-client-atom)))
