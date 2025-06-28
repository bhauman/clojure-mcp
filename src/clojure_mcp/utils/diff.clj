(ns clojure-mcp.utils.diff
  (:require [clojure.string :as str])
  (:import
   (com.github.difflib DiffUtils UnifiedDiffUtils)))

(defn generate-unified-diff
  "Generate a unified diff format from original and revised text strings.
   Takes the same 3 arguments as the shell version:
   - file1-content: original text as string
   - file2-content: revised text as string
   - context-lines: number of context lines (defaults to 3)"
  ([file1-content file2-content]
   (generate-unified-diff file1-content file2-content 3))
  ([file1-content file2-content context-lines]
   (let [original-lines (str/split-lines file1-content)
         revised-lines (str/split-lines file2-content)
         patch (DiffUtils/diff original-lines revised-lines)
         unified-diff (UnifiedDiffUtils/generateUnifiedDiff
                       "original.txt" ;; default filename
                       "revised.txt"  ;; default filename
                       original-lines
                       patch
                       context-lines)]
     (str/join "\n" unified-diff))))
