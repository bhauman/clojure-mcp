(ns clojure-mcp.subprocess-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [clojure-mcp.subprocess :as subprocess]))

(deftest parse-port-from-output-test
  (testing "parses standard nREPL output formats"
    (is (= 7888 (subprocess/parse-port-from-output "nREPL server started on port 7888")))
    (is (= 7888 (subprocess/parse-port-from-output "Started nREPL on port: 7888")))
    (is (= 7888 (subprocess/parse-port-from-output "Port: 7888")))
    (is (= 7888 (subprocess/parse-port-from-output "nREPL Server started on port 7888 on host localhost"))))

  (testing "parses case-insensitive formats"
    (is (= 7888 (subprocess/parse-port-from-output "NREPL server started on PORT 7888")))
    (is (= 7888 (subprocess/parse-port-from-output "started nrepl on port: 7888"))))

  (testing "parses standalone port numbers"
    (is (= 7888 (subprocess/parse-port-from-output "7888")))
    (is (= 54321 (subprocess/parse-port-from-output "Starting on 54321")))
    (is (= 1234 (subprocess/parse-port-from-output "Some output\n1234\nMore output"))))

  (testing "validates port range"
    (is (nil? (subprocess/parse-port-from-output "Port: 80"))) ; Too low
    (is (nil? (subprocess/parse-port-from-output "Port: 99999"))) ; Too high
    (is (= 1024 (subprocess/parse-port-from-output "Port: 1024"))) ; Min valid
    (is (= 65535 (subprocess/parse-port-from-output "Port: 65535")))) ; Max valid

  (testing "handles invalid or missing input"
    (is (nil? (subprocess/parse-port-from-output nil))) (is (nil? (subprocess/parse-port-from-output "")))
    (is (nil? (subprocess/parse-port-from-output "No port information here")))
    (is (nil? (subprocess/parse-port-from-output "Port: abc"))))

  (testing "finds first valid port when multiple present"
    (is (= 7888 (subprocess/parse-port-from-output "Port 80 is taken, using 7888 instead")))
    (is (= 1234 (subprocess/parse-port-from-output "Invalid port 99999, using 1234"))))

  (testing "handles real-world nREPL output examples"
    ;; Leiningen output
    (is (= 7888 (subprocess/parse-port-from-output
                 "nREPL server started on port 7888 on host 127.0.0.1 - nrepl://127.0.0.1:7888")))

    ;; Shadow-cljs output
    (is (= 9000 (subprocess/parse-port-from-output
                 "shadow-cljs - nREPL server available at nrepl://localhost:9000")))

    ;; deps.edn with nREPL middleware
    (is (= 45678 (subprocess/parse-port-from-output
                  "Started nREPL server on port 45678\nnREPL client can be connected to port 45678")))

    ;; Boot output
    (is (= 12345 (subprocess/parse-port-from-output
                  "Starting boot repl...\nREPL server listening on port 12345")))))

(deftest wait-for-port-in-output-test
  (testing "handles process termination scenarios"
    ;; This test would require mocking java.lang.Process
    ;; For now, we'll test the basic structure
    (is (fn? subprocess/wait-for-port-in-output))))

(deftest execute-start-nrepl-cmd-test
  (testing "validates command parameters"
    (is (= 7888
           (subprocess/start-nrepl-cmd-and-parse-port
            "echo 'nREPL server started on port 7888'"
            5000)))))

(deftest read-nrepl-port-file-test
  (testing "reads valid port from file"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-read-port-1")]
      (try
        (spit temp-port-file "8888")
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (is (= 8888 (subprocess/read-nrepl-port-file))))
        (finally
          (.delete temp-port-file)))))

  (testing "validates port range from file"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-read-port-2")]
      (try
        (spit temp-port-file "99999") ; Invalid port
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Port number out of valid range"
               (subprocess/read-nrepl-port-file))))
        (finally
          (.delete temp-port-file)))))

  (testing "handles invalid file content"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-read-port-3")]
      (try
        (spit temp-port-file "not-a-number")
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Invalid port number"
               (subprocess/read-nrepl-port-file))))
        (finally
          (.delete temp-port-file)))))

  (testing "handles missing file"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-read-port-4")]
      (when (.exists temp-port-file) (.delete temp-port-file))
      (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #".nrepl-port file not found"
             (subprocess/read-nrepl-port-file)))))))

(deftest start-nrepl-and-read-port-file-test
  (testing "coordinates command execution with file reading"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-nrepl-port")
          cmd (str "echo '9999' > " (.getAbsolutePath temp-port-file))]
      (try
        ;; Ensure clean state
        (when (.exists temp-port-file) (.delete temp-port-file))

        ;; Test coordination with rebound file path
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (let [result (subprocess/start-nrepl-and-read-port-file cmd 10000)]
            (is (= 9999 result))))
        (finally
          (when (.exists temp-port-file) (.delete temp-port-file))))))

  (testing "removes existing file before starting command"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-nrepl-port-2")
          cmd (str "echo '7777' > " (.getAbsolutePath temp-port-file))]
      (try
        ;; Create a stale file
        (spit temp-port-file "1111")
        (is (.exists temp-port-file))

        ;; Test that coordination removes it and creates fresh one
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (let [result (subprocess/start-nrepl-and-read-port-file cmd 10000)]
            (is (= 7777 result))))
        (finally
          (when (.exists temp-port-file) (.delete temp-port-file))))))

  (testing "handles command timeout"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-nrepl-port-3")
          cmd "sleep 10"] ; Command that won't create the file
      (try
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Timeout waiting for .nrepl-port file creation"
               (subprocess/start-nrepl-and-read-port-file cmd 1000))))
        (finally
          (when (.exists temp-port-file) (.delete temp-port-file)))))))

(deftest poll-for-nrepl-port-file-test
  (testing "waits for file creation"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-poll-port-1")
          start-time (System/currentTimeMillis)]
      (try
        ;; Ensure file doesn't exist
        (when (.exists temp-port-file) (.delete temp-port-file))

        ;; Start polling in background and create file after delay
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (future
            (Thread/sleep 500)
            (spit temp-port-file "5555"))

          (let [result (subprocess/poll-for-nrepl-port-file 5000 start-time)]
            (is (= 5555 result))))
        (finally
          (when (.exists temp-port-file) (.delete temp-port-file))))))

  (testing "ignores stale files"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-poll-port-2")
          start-time (System/currentTimeMillis)]
      (try
        ;; Create a file that predates our start time
        (spit temp-port-file "3333")
        (.setLastModified temp-port-file (- start-time 1000)) ; 1 second before start

        ;; Start polling in background and create fresh file after delay
        (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
          (future
            (Thread/sleep 500)
            (spit temp-port-file "4444")) ; This will have current timestamp

          (let [result (subprocess/poll-for-nrepl-port-file 5000 start-time)]
            (is (= 4444 result))))
        (finally
          (when (.exists temp-port-file) (.delete temp-port-file))))))

  (testing "times out when file never appears"
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          temp-port-file (io/file temp-dir "test-poll-port-3")
          start-time (System/currentTimeMillis)]
      (when (.exists temp-port-file) (.delete temp-port-file))
      (binding [subprocess/*nrepl-port-file-path* (constantly (.getAbsolutePath temp-port-file))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Timeout waiting for .nrepl-port file creation"
             (subprocess/poll-for-nrepl-port-file 1000 start-time)))))))
