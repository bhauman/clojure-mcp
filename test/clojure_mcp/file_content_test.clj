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
