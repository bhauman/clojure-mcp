(ns clojure-mcp.file-content-test
  "Tests for file-content namespace, particularly MIME type detection"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure-mcp.file-content :as fc]
   [clojure.java.io :as io]))

(def ^:dynamic *test-dir* nil)

(defn test-dir-fixture [f]
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "file-content-test-" (System/currentTimeMillis)))]
    (.mkdirs temp-dir)
    (binding [*test-dir* temp-dir]
      (try
        (f)
        (finally
          (doseq [file (.listFiles temp-dir)]
            (.delete file))
          (.delete temp-dir))))))

(use-fixtures :each test-dir-fixture)

(defn create-test-file
  "Helper to create a test file with content"
  [filename content]
  (let [file (io/file *test-dir* filename)]
    (spit file content)
    (.getAbsolutePath file)))

(deftest text-media-type-test
  (testing "Standard text files are recognized as text via Tika hierarchy"
    (is (fc/text-media-type? "text/plain"))
    (is (fc/text-media-type? "text/html"))
    (is (fc/text-media-type? "text/css"))
    (is (fc/text-media-type? "text/csv"))
    (is (fc/text-media-type? "text/markdown"))
    (is (fc/text-media-type? "text/x-clojure"))
    (is (fc/text-media-type? "text/x-java"))
    (is (fc/text-media-type? "text/x-python")))

  (testing "Specific application types that should be treated as text"
    ;; These are specifically handled by our patterns
    (is (fc/text-media-type? "application/json"))
    (is (fc/text-media-type? "application/xml"))
    (is (fc/text-media-type? "application/sql"))
    (is (fc/text-media-type? "application/yaml"))
    (is (fc/text-media-type? "application/x-yaml")))

  (testing "MIME types (with parameters) are handled via Tika hierarchy interestingly"
    ;; Tika's hierarchy checks accept these
    (is (fc/text-media-type? "application/json; charset=utf-8"))
    (is (fc/text-media-type? "text/plain; charset=iso-8859-1")))

  (testing "Case-insensitive MIME types per RFC 2045"
    (is (fc/text-media-type? "APPLICATION/JSON"))
    (is (fc/text-media-type? "Application/Json"))
    (is (fc/text-media-type? "APPLICATION/SQL"))
    (is (fc/text-media-type? "Application/Xml"))
    (is (fc/text-media-type? "APPLICATION/YAML")))

  (testing "Invalid MIME strings that cause MediaType/parse to return null"
    (is (not (fc/text-media-type? "not/a/valid/mime")))
    (is (not (fc/text-media-type? "text/")))
    (is (not (fc/text-media-type? "/json")))
    (is (not (fc/text-media-type? ";;;invalid")))
    (is (not (fc/text-media-type? " ")))
    (is (not (fc/text-media-type? "")))
    (is (not (fc/text-media-type? nil))))

  (testing "Binary types are not recognized as text"
    (is (not (fc/text-media-type? "application/pdf")))
    (is (not (fc/text-media-type? "application/octet-stream")))
    (is (not (fc/text-media-type? "image/png")))
    (is (not (fc/text-media-type? "image/jpeg")))
    (is (not (fc/text-media-type? "audio/mpeg")))
    (is (not (fc/text-media-type? "video/mp4")))))

(deftest mime-type-detection-test
  (testing "MIME type detection for specifically supported file types"
    (let [sql-file (create-test-file "test.sql" "SELECT * FROM users;")
          mt (fc/mime-type sql-file)]
      (is (fc/text-media-type? mt) "SQL should be treated as text regardless of exact MIME value")
      (is (fc/text-file? sql-file)))

    (let [json-file (create-test-file "test.json" "{\"key\": \"value\"}")]
      (is (fc/text-file? json-file)))

    (let [xml-file (create-test-file "test.xml" "<root><child/></root>")]
      (is (fc/text-file? xml-file)))

    (let [yaml-file (create-test-file "test.yaml" "key: value\nlist:\n  - item1")]
      (is (fc/text-file? yaml-file)))))

(deftest image-media-type-test
  (testing "Image MIME types are correctly identified"
    (is (fc/image-media-type? "image/png"))
    (is (fc/image-media-type? "image/jpeg"))
    (is (fc/image-media-type? "image/gif"))
    (is (fc/image-media-type? "image/svg+xml"))
    (is (not (fc/image-media-type? "text/plain")))
    (is (not (fc/image-media-type? "application/pdf")))))

(deftest text-like-mime-patterns-test
  (testing "Text-like MIME patterns match standard SQL, JSON, YAML, and XML types"
    ;; Verify the patterns exist and match expected types
    (is (some? fc/text-like-mime-patterns))
    (is (vector? fc/text-like-mime-patterns))

    (let [should-match ["application/sql"
                        "application/json"
                        "application/xml"
                        "application/yaml"
                        "application/x-yaml"]
          should-not-match ["application/pdf"
                            "application/octet-stream"
                            "image/png"
                            "text/json" ;; Wrong prefix
                            "json"]] ;; No prefix

      (doseq [mime should-match]
        (testing (str "Pattern should match: " mime)
          (is (some #(re-matches % mime) fc/text-like-mime-patterns)
              (str "No pattern matched for " mime))))

      (doseq [mime should-not-match]
        (testing (str "Pattern should not match: " mime)
          (is (not (some #(re-matches % mime) fc/text-like-mime-patterns))
              (str "Pattern incorrectly matched for " mime)))))))

(deftest get-filename-test
  (testing "Extracts filename correctly"
    (is (= "file.org" (fc/get-filename "/path/to/file.org")))
    (is (= ".gitignore" (fc/get-filename "/path/to/.gitignore")))
    (is (= "makefile" (fc/get-filename "/path/to/Makefile"))))

  (testing "Handles edge cases"
    (is (nil? (fc/get-filename nil)))
    (is (nil? (fc/get-filename "")))))

(deftest get-file-extension-test
  (testing "Extracts file extension correctly"
    (is (= ".org" (fc/get-file-extension "/path/to/file.org")))
    (is (= ".clj" (fc/get-file-extension "/path/to/file.clj")))
    (is (= ".gz" (fc/get-file-extension "/path/to/file.tar.gz"))) ; Returns last extension
    (is (= ".org" (fc/get-file-extension "file.org"))))

  (testing "Handles case-insensitivity"
    (is (= ".org" (fc/get-file-extension "/path/to/FILE.ORG")))
    (is (= ".md" (fc/get-file-extension "README.MD"))))

  (testing "Returns nil for files without extensions"
    (is (nil? (fc/get-file-extension "/path/to/file")))
    (is (nil? (fc/get-file-extension "Makefile"))))

  (testing "Handles dotfiles correctly"
    (is (nil? (fc/get-file-extension ".gitignore")))
    (is (= ".local" (fc/get-file-extension ".gitignore.local"))))

  (testing "Handles edge cases"
    (is (nil? (fc/get-file-extension nil)))
    (is (nil? (fc/get-file-extension "")))))

(deftest text-extension-test
  (testing "Known text extensions are recognized"
    (is (fc/text-extension? "/path/to/file.org"))
    (is (fc/text-extension? "/path/to/file.md"))
    (is (fc/text-extension? "/path/to/file.txt"))
    (is (fc/text-extension? "/path/to/file.rst"))
    (is (fc/text-extension? "/path/to/file.csv"))
    (is (fc/text-extension? "/path/to/file.log"))
    (is (fc/text-extension? "/path/to/file.conf")))

  (testing "Known text filenames (dotfiles, etc.) are recognized"
    (is (fc/text-extension? "/path/to/.gitignore"))
    (is (fc/text-extension? "/path/to/.dockerignore"))
    (is (fc/text-extension? "/path/to/Makefile"))
    (is (fc/text-extension? "/path/to/Dockerfile")))

  (testing "Case-insensitive extension matching"
    (is (fc/text-extension? "/path/to/file.ORG"))
    (is (fc/text-extension? "/path/to/file.Md"))
    (is (fc/text-extension? "/path/to/FILE.TXT")))

  (testing "Case-insensitive filename matching"
    (is (fc/text-extension? "/path/to/MAKEFILE"))
    (is (fc/text-extension? "/path/to/.GITIGNORE")))

  (testing "Unknown extensions return false"
    (is (not (fc/text-extension? "/path/to/file.pdf")))
    (is (not (fc/text-extension? "/path/to/file.exe")))
    (is (not (fc/text-extension? "/path/to/file.png")))))

(deftest org-file-text-detection-test
  (testing ".org files are treated as text despite Tika MIME detection"
    (let [org-file (create-test-file "test.org" "* Heading\n** Subheading\n- bullet point")]
      ;; The file should be recognized as text due to extension override
      (is (fc/text-file? org-file)
          ".org files should be treated as text files")
      ;; Also test case insensitivity
      (is (fc/text-extension? org-file))))

  (testing "Various org-mode content patterns work"
    (let [org-content "#+TITLE: My Document
#+AUTHOR: Test Author

* Introduction
This is an org-mode document.

** Section 1
- Item 1
- Item 2

#+BEGIN_SRC clojure
(defn hello [] (println \"Hello\"))
#+END_SRC
"
          org-file (create-test-file "document.org" org-content)]
      (is (fc/text-file? org-file)))))
