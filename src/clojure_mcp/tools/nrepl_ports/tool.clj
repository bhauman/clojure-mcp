(ns clojure-mcp.tools.nrepl-ports.tool
  "MCP tool implementation for listing nREPL ports.
   Discovers running nREPL servers and provides detailed information about each."
  (:require
   [clojure-mcp.tool-system :as tool-system]
   [clojure-mcp.tools.nrepl-ports.core :as core]
   [clojure-mcp.config :as config]
   [clojure.string :as string]))

;; Factory function to create the tool configuration
(defn create-list-nrepl-ports-tool
  "Creates the list-nrepl-ports tool configuration.

   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  {:tool-type :list-nrepl-ports
   :nrepl-client-atom nrepl-client-atom})

;; Implement the required multimethods for the list-nrepl-ports tool
(defmethod tool-system/tool-name :list-nrepl-ports [_]
  "list_nrepl_ports")

(defmethod tool-system/tool-description :list-nrepl-ports [_]
  "Discover and list all running nREPL servers on this machine.
 - Checks for .nrepl-port files in the current directory
 - Scans for Java/Clojure/Babashka processes listening on TCP ports
 - Validates each port responds to nREPL protocol
 - Returns detailed info including environment type (clj, bb, shadow, etc.) and project directory
 - Useful for finding available REPLs to connect to")

(defmethod tool-system/tool-schema :list-nrepl-ports [_]
  {:type :object
   :properties {:explanation {:type :string
                              :description "Brief explanation of why you need to list nREPL ports"}}
   :required [:explanation]})

(defmethod tool-system/validate-inputs :list-nrepl-ports [{:keys [nrepl-client-atom]} _inputs]
  (let [nrepl-client-map @nrepl-client-atom
        current-dir (config/get-nrepl-user-dir nrepl-client-map)]
    {:current-dir (or current-dir (System/getProperty "user.dir"))}))

(defmethod tool-system/execute-tool :list-nrepl-ports [_ inputs]
  (let [{:keys [current-dir]} inputs]
    (core/discover-nrepl-ports current-dir)))

(defn format-env-type
  "Format environment type for display (concise)."
  [env-type]
  (case env-type
    :clj "clj"
    :bb "bb"
    :basilisp "basilisp"
    :scittle "scittle"
    :shadow "shadow"
    :unknown "unknown"
    "n/a"))

(defn format-port-info
  "Format a single port info map for concise display."
  [port-info show-project-dir?]
  (let [{:keys [host port valid env-type project-dir]} port-info]
    (if valid
      (str "  " host ":" port " (" (format-env-type env-type) ")"
           (when (and show-project-dir? project-dir)
             (str " - " project-dir)))
      (str "  " host ":" port " (not responding)"))))

(defmethod tool-system/format-results :list-nrepl-ports [_ result]
  (if (empty? result)
    {:result ["No nREPL servers found.\n\nTo start a Clojure REPL with nREPL, run:\n  clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.3.0\"}}}' -M -m nrepl.cmdline"]
     :error false}
    (let [valid-ports (filter :valid result)
          current-project-ports (filter :matches-cwd valid-ports)
          other-project-ports (remove :matches-cwd valid-ports)
          current-count (count current-project-ports)
          other-count (count other-project-ports)
          total-count (count valid-ports)
          current-dir (some :project-dir current-project-ports)

          output (str "Discovered nREPL servers:\n"
                      (when (seq current-project-ports)
                        (str "\nIn current directory"
                             (when current-dir (str " (" current-dir ")"))
                             ":\n"
                             (string/join "\n" (map #(format-port-info % false) current-project-ports))
                             "\n"))
                      (when (seq other-project-ports)
                        (str "\nIn other directories:\n"
                             (string/join "\n" (map #(format-port-info % true) other-project-ports))
                             "\n"))
                      (str "\nTotal: " total-count " server" (when (not= 1 total-count) "s")
                           (when (and (pos? current-count) (pos? other-count))
                             (str " (" current-count " in current directory, "
                                  other-count " in other directories)"))))]
      {:result [output]
       :error false})))

;; Backward compatibility function that returns the registration map
(defn list-nrepl-ports-tool
  "Returns the registration map for the list-nrepl-ports tool.

   Parameters:
   - nrepl-client-atom: Atom containing the nREPL client"
  [nrepl-client-atom]
  (tool-system/registration-map (create-list-nrepl-ports-tool nrepl-client-atom)))
