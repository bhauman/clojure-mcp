(ns clojure-mcp.tools.scratch-pad.core
  (:require
   [clojure.pprint :as pprint]
   [clojure-mcp.tools.scratch-pad.truncate :as truncate]
   [clojure-mcp.tools.scratch-pad.smart-path :as sp]))

(defn inspect-data [data]
  (if (empty? data)
    "Empty scratch pad"
    (with-out-str (pprint/pprint data))))

(defn execute-set-path
  "Execute a set_path operation and return the result map."
  [current-data path value]
  (let [new-data (sp/smart-assoc-in current-data path value)
        stored-value (sp/smart-get-in new-data path)]
    {:data new-data
     :result {:stored-at path
              :value stored-value
              :pretty-value (with-out-str (pprint/pprint stored-value))}}))

(defn execute-get-path
  "Execute a get_path operation and return the result map."
  [current-data path]
  (let [value (sp/smart-get-in current-data path)]
    {:result {:path path
              :value value
              :pretty-value (when (some? value)
                              (with-out-str (pprint/pprint value)))
              :found (some? value)}}))

(defn execute-delete-path
  "Execute a delete_path operation and return the result map."
  [current-data path]
  (let [new-data (sp/smart-dissoc-in current-data path)]
    {:data new-data
     :result {:removed-from path}}))

(defn execute-inspect
  "Execute an inspect operation and return the result map."
  [current-data depth path]
  (let [data-to-view (if (and path (not (empty? path)))
                       (sp/smart-get-in current-data path)
                       current-data)]
    (if (nil? data-to-view)
      {:result {:tree (str "No data found at path " path)}}
      {:result {:tree (truncate/pprint-truncated data-to-view depth)}})))
