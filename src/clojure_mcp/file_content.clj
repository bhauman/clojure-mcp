(ns clojure-mcp.file-content
  "File content utilities for MCP, including image content creation."
  (:require [clojure.string :as str])
  (:import [io.modelcontextprotocol.spec
            McpSchema$ImageContent
            McpSchema$EmbeddedResource
            McpSchema$BlobResourceContents
            McpSchema$TextResourceContents]
           [java.nio.file Path Files]
           [java.util Base64]
           [org.apache.tika Tika]
           [org.apache.tika.mime MimeTypes MediaTypeRegistry MediaType]))

;; embedded resources aren't supported by claude desktop yet but who knows
;; which clients are supporting this and when

(def ^Tika mime-detector
  "Singleton Apache Tika detector (falls back to Files/probeContentType)."
  (delay (Tika.)))

(def ^MediaTypeRegistry registry
  (.getMediaTypeRegistry (MimeTypes/getDefaultMimeTypes)))

(def text-file-extensions
  "File extensions (including dot) that should always be treated as text,
   regardless of MIME type detection. This handles cases where Tika misdetects
   certain text formats (e.g., .org files detected as application/vnd.lotus-organizer)."
  #{".org" ; Emacs Org-mode (misdetected as Lotus Organizer)
    ".md" ; Markdown
    ".markdown" ; Markdown (alternative extension)
    ".rst" ; reStructuredText
    ".adoc" ; AsciiDoc
    ".textile" ; Textile
    ".txt" ; Plain text
    ".text" ; Plain text (alternative)
    ".csv" ; CSV (sometimes misdetected)
    ".tsv" ; TSV
    ".log" ; Log files
    ".conf" ; Configuration files
    ".cfg" ; Configuration files
    ".ini" ; INI files
    ".properties" ; Java properties
    ".env"}) ; Environment files

(def text-file-names
  "Full filenames (case-insensitive) that should be treated as text.
   Used for dotfiles and other files without traditional extensions."
  #{".gitignore"
    ".gitattributes"
    ".dockerignore"
    ".editorconfig"
    ".npmignore"
    ".eslintignore"
    ".prettierignore"
    "makefile"
    "dockerfile"
    "rakefile"
    "gemfile"
    "procfile"})

(def text-like-mime-patterns
  "Regex patterns for MIME types that should be treated as text.
   Covers common text-based data formats used in projects that don't
   inherit from text/plain in the Apache Tika MediaType hierarchy."
  [#"^application/(sql|json|xml|(?:x-)?yaml)$"])

(defn text-media-type?
  "Determines if a MIME type represents text content.
   Uses Apache Tika's type hierarchy plus additional patterns for
   common text-based formats that don't inherit from text/plain.
   Handles invalid MIME strings gracefully."
  [mime]
  (let [s (some-> mime str str/lower-case)
        text-according-to-tika-hierarchy?
        (try
          (.isInstanceOf registry (MediaType/parse s) MediaType/TEXT_PLAIN)
          (catch IllegalArgumentException _ false))]
    (boolean (or text-according-to-tika-hierarchy?
                 (and s (some #(re-matches % s) text-like-mime-patterns))))))

(defn image-media-type? [mime-or-media-type]
  (= "image" (.getType (MediaType/parse mime-or-media-type))))

(defn mime-type* [^Path p]
  (or (Files/probeContentType p)
      (try (.detect ^Tika @mime-detector (.toFile p))
           (catch Exception _ "application/octet-stream"))))

(defn get-filename
  "Returns the lowercase filename from a file path, or nil for nil/empty input."
  [file-path]
  (when (and file-path (seq (str file-path)))
    (-> (str file-path) (str/split #"[/\\]") last str/lower-case)))

(defn get-file-extension
  "Returns the lowercase file extension including the dot, or nil if none.
   Example: \"/path/to/file.ORG\" -> \".org\""
  [file-path]
  (when-let [path-str (str file-path)]
    (let [filename (-> path-str (str/split #"[/\\]") last)
          dot-idx (str/last-index-of filename ".")]
      (when (and dot-idx (pos? dot-idx))
        (str/lower-case (subs filename dot-idx))))))

(defn text-extension?
  "Returns true if the file path has a known text file extension,
   or if the filename itself is a known text file (e.g., Makefile, .gitignore)."
  [file-path]
  (or (contains? text-file-extensions (get-file-extension file-path))
      (contains? text-file-names (get-filename file-path))))

(defn str->nio-path [fp]
  (Path/of fp (make-array String 0)))

(defn mime-type [file-path]
  (mime-type* (str->nio-path file-path)))

(defn serialized-file [file-path]
  (let [path (str->nio-path file-path)
        bytes (Files/readAllBytes path)
        b64 (.encodeToString (Base64/getEncoder) bytes)
        mime (mime-type* path) ;; e.g. application/pdf
        uri (str "file://" (.toAbsolutePath path))]
    {:file-path file-path
     :nio-path path
     :uri uri
     :mime-type mime
     :b64 b64}))

(defn image-content [{:keys [b64 mime-type]}]
  (McpSchema$ImageContent. nil nil b64 mime-type))

(defn text-resource-content [{:keys [uri mime-type b64]}]
  (let [blob (McpSchema$TextResourceContents. uri mime-type b64)]
    (McpSchema$EmbeddedResource. nil nil blob)))

(defn binary-resource-content [{:keys [uri mime-type b64]}]
  (let [blob (McpSchema$BlobResourceContents. uri mime-type b64)]
    (McpSchema$EmbeddedResource. nil nil blob)))

(defn file-response->file-content [{:keys [::file-response]}]
  (let [{:keys [mime-type] :as ser-file} (serialized-file file-response)]
    (cond
      (text-media-type? mime-type) (text-resource-content ser-file)
      (image-media-type? mime-type) (image-content ser-file)
      :else (binary-resource-content ser-file))))

(defn should-be-file-response? [file-path]
  (not (text-media-type? (mime-type file-path))))

(defn text-file?
  "Returns true if the file should be treated as a text file.
   First checks if the file has a known text extension (to handle
   cases where MIME detection fails), then falls back to MIME type check."
  [file-path]
  (or (text-extension? file-path)
      (text-media-type? (mime-type file-path))))

(defn image-file? [file-path]
  (image-media-type? (mime-type file-path)))

(defn ->file-response [file-path]
  {::file-response file-path})

(defn file-response? [map]
  (and (map? map)
       (::file-response map)))

