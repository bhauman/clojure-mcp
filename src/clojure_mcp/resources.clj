(ns clojure-mcp.resources
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure-mcp.nrepl :as mcp-nrepl]
            [clojure-mcp.config :as config] ; Added config require
            [clojure-mcp.tools.project.core :as project])
  (:import [io.modelcontextprotocol.spec McpSchema$Resource McpSchema$ReadResourceResult]))

(defn read-file [full-path]
  (let [file (io/file full-path)]
    (if (.exists file)
      (try
        (slurp file)
        (catch Exception e
          (throw (ex-info (str "reading file- " full-path
                               "\nException- " (.getMessage e))
                          {:path full-path}
                          e))))
      (throw (ex-info (str "File not found- " full-path
                           "\nAbsolute path- " (.getAbsolutePath file))
                      {:path full-path})))))

(defn create-file-resource
  "Creates a resource specification for serving a file.
   Takes a full file path resolved with the correct working directory."
  [url name description mime-type full-path]
  {:url url
   :name name
   :description description
   :mime-type mime-type
   :resource-fn
   (fn [_ _ clj-result-k]
     (try
       (let [result (read-file full-path)]
         (clj-result-k [result]))
       (catch Exception e
         (clj-result-k [(str "Error in resource function: "
                             (ex-message e)
                             "\nFor file: " full-path)]))))})

(defn create-string-resource
  "Creates a resource specification for serving a string.
   Accepts nrepl-client-atom for consistency with create-file-resource, but doesn't use it.

   :contents should be a vector of strings"
  [url name description mime-type contents & [nrepl-client-atom]]
  {:url url
   :name name
   :description description
   :mime-type mime-type
   :resource-fn (fn [_ _ clj-result-k]
                  (clj-result-k contents))})
