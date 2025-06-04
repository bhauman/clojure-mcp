(ns clojure-mcp.tools.scratch-pad.core
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]))

(defn assoc-in-data [data path value]
  (assoc-in data path value))

(defn get-in-data [data path]
  (get-in data path))

(defn dissoc-in-data [data path]
  (if (empty? path)
    data
    (if (= 1 (count path))
      (dissoc data (first path))
      (update-in data (butlast path) dissoc (last path)))))

(defn tree-view [data]
  (if (empty? data)
    "Empty scratch pad"
    (with-out-str (pprint/pprint data))))
