(ns clojure-mcp.dialects
  "Handles environment-specific behavior for different nREPL dialects.

   Provides dialect-specific expressions for initialization sequences.
   The actual execution of these expressions is handled by clojure-mcp.nrepl."
  (:require [clojure.java.io :as io]
            [clojure-mcp.utils.file :as file-utils]))

(defn handle-bash-over-nrepl? [nrepl-env-type]
  (boolean (#{:clj :bb} nrepl-env-type)))

;; Multimethod for getting the expression to fetch project directory
(defmulti fetch-project-directory-exp
  "Returns an expression (string) to evaluate for getting the project directory.
   Dispatches on :nrepl-env-type from config."
  (fn [nrepl-env-type] nrepl-env-type))

(defmethod fetch-project-directory-exp :clj
  [_]
  "(System/getProperty \"user.dir\")")

(defmethod fetch-project-directory-exp :bb
  [_]
  "(System/getProperty \"user.dir\")")

(defmethod fetch-project-directory-exp :basilisp
  [_]
  "(import os)\n(os/getcwd)")

(defmethod fetch-project-directory-exp :default
  [_]
  nil)

;; Multimethod for environment initialization
(defmulti initialize-environment-exp
  "Returns a vector of expressions (strings) to evaluate for initializing
   the environment. These set up necessary namespaces and helpers."
  (fn [nrepl-env-type] nrepl-env-type))

(defmethod initialize-environment-exp :clj
  [_]
  ["(require '[clojure.repl :as repl])"
   "(require 'nrepl.util.print)"])

(defmethod initialize-environment-exp :bb
  [_]
  ["(require '[clojure.repl :as repl])"])

(defmethod initialize-environment-exp :basilisp
  [_]
  ["(require '[basilisp.repl :as repl])"])

(defmethod initialize-environment-exp :default
  [_]
  [])

;; Helper to load REPL helpers - might vary by environment
(defmulti load-repl-helpers-exp
  "Returns expressions for loading REPL helper functions.
   Some environments might not support all helpers."
  (fn [nrepl-env-type] nrepl-env-type))

(defmethod load-repl-helpers-exp :clj
  [_]
  ;; For Clojure, we load the helpers from resources
  [(file-utils/slurp-utf8 (io/resource "clojure-mcp/repl_helpers.clj"))
   "(in-ns 'user)"])

(defmethod load-repl-helpers-exp :default
  [_]
  [])
