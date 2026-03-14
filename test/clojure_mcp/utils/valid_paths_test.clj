(ns clojure-mcp.utils.valid-paths-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure-mcp.utils.valid-paths :as valid-paths]))

(deftest extract-paths-from-bash-command-test
  (testing "Basic path extraction"
    (is (= #{"/usr/bin"}
           (valid-paths/extract-paths-from-bash-command "ls /usr/bin")))

    (is (= #{"./file.txt"}
           (valid-paths/extract-paths-from-bash-command "cat ./file.txt")))

    (is (= #{"."}
           (valid-paths/extract-paths-from-bash-command "find . -name '*.clj'")))

    (is (= #{"../other"}
           (valid-paths/extract-paths-from-bash-command "cd ../other")))

    (is (= #{"~/.bashrc"}
           (valid-paths/extract-paths-from-bash-command "ls ~/.bashrc"))))

  (testing "Multiple paths"
    (is (= #{"/path1" "/path2" "../path3"}
           (valid-paths/extract-paths-from-bash-command "ls /path1 /path2 ../path3")))

    (is (= #{"/etc" "/home"}
           (valid-paths/extract-paths-from-bash-command "tar -czf backup.tar.gz /etc /home"))))

  (testing "Quoted paths with spaces"
    (is (= #{"/src/file" "/dest with spaces/"}
           (valid-paths/extract-paths-from-bash-command "cp /src/file \"/dest with spaces/\"")))

    (is (= #{"/path with spaces"}
           (valid-paths/extract-paths-from-bash-command "ls '/path with spaces'"))))

  (testing "Complex commands with pipes and redirections"
    (is (= #{"/usr" "/tmp/out.txt"}
           (valid-paths/extract-paths-from-bash-command "find /usr -name '*.txt' | head > /tmp/out.txt")))

    (is (= #{"/var/log/app.log" "/tmp/errors.log"}
           (valid-paths/extract-paths-from-bash-command "cat /var/log/app.log | grep ERROR | tee /tmp/errors.log"))))

  (testing "False positives should be avoided"
    (is (= #{}
           (valid-paths/extract-paths-from-bash-command "echo 'not/a/path really'")))

    (is (= #{}
           (valid-paths/extract-paths-from-bash-command "grep 'pattern/with/slashes' file.txt")))

    (is (= #{}
           (valid-paths/extract-paths-from-bash-command "sed 's/old/new/g' input.txt"))))

  (testing "Security-relevant paths"
    (is (= #{"../../../../etc/passwd"}
           (valid-paths/extract-paths-from-bash-command "cat ../../../../etc/passwd")))

    (is (= #{"~/.ssh/"}
           (valid-paths/extract-paths-from-bash-command "ls ~/.ssh/")))

    (is (= #{"/etc/passwd" "./innocent-file"}
           (valid-paths/extract-paths-from-bash-command "ln -s /etc/passwd ./innocent-file"))))

  (testing "Edge cases"
    (is (= nil
           (valid-paths/extract-paths-from-bash-command "")))

    (is (= nil
           (valid-paths/extract-paths-from-bash-command nil)))

    (is (= #{}
           (valid-paths/extract-paths-from-bash-command "ps aux")))

    (is (= #{}
           (valid-paths/extract-paths-from-bash-command "echo hello world")))))

(deftest preprocess-path-test
  (testing "Home directory expansion"
    (let [home (System/getProperty "user.home")]
      (is (= (str home "/config")
             (valid-paths/preprocess-path "~/config")))

      (is (= (str home "/some/deep/path")
             (valid-paths/preprocess-path "~/some/deep/path")))

      (is (= home
             (valid-paths/preprocess-path "~")))))

  (testing "Other paths unchanged"
    (is (= "/absolute/path"
           (valid-paths/preprocess-path "/absolute/path")))

    (is (= "./relative"
           (valid-paths/preprocess-path "./relative")))

    (is (= "../parent"
           (valid-paths/preprocess-path "../parent")))

    (is (= "."
           (valid-paths/preprocess-path ".")))

    (is (= ".."
           (valid-paths/preprocess-path "..")))))

(deftest clojure-file?-test
  (testing "Detect Babashka shebang"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir") "bb-script.sh")]
      (spit tmp "#!/usr/bin/env bb\n(println :hi)")
      (is (valid-paths/clojure-file? (.getPath tmp)))
      (.delete tmp)))
  (testing "Regular bash script not detected"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir") "bash-script.sh")]
      (spit tmp "#!/bin/bash\necho hi")
      (is (not (valid-paths/clojure-file? (.getPath tmp))))
      (.delete tmp))))

(deftest dash-to-underscore-correction-test
  (let [test-dir (io/file (System/getProperty "java.io.tmpdir") "valid-paths-dash-test")
        canonical-dir (.getCanonicalPath test-dir)
        make-client (fn []
                      {:clojure-mcp.config/config
                       {:nrepl-user-dir canonical-dir
                        :allowed-directories [canonical-dir]}})]
    (try
      (.mkdirs test-dir)

      (testing "Clojure file with dashes corrected to underscores when underscore version exists"
        (let [underscore-file (io/file test-dir "core_stuff.clj")
              _ (spit underscore-file "(ns core-stuff)")
              dash-path (.getAbsolutePath (io/file test-dir "core-stuff.clj"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (testing "Non-Clojure files (.java) do NOT get corrected"
        (let [underscore-file (io/file test-dir "core_stuff.java")
              _ (spit underscore-file "public class core_stuff {}")
              dash-path (.getAbsolutePath (io/file test-dir "core-stuff.java"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          ;; Should return the original (dashed) path, not corrected
          (is (not= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (testing "Non-Clojure files (.py) do NOT get corrected"
        (let [underscore-file (io/file test-dir "my_module.py")
              _ (spit underscore-file "def hello(): pass")
              dash-path (.getAbsolutePath (io/file test-dir "my-module.py"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (not= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (testing "File where dashed version exists is returned as-is"
        (let [dash-file (io/file test-dir "core-stuff.clj")
              _ (spit dash-file "(ns core-stuff)")
              dash-path (.getAbsolutePath dash-file)
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (= (.getCanonicalPath dash-file) result))
          (.delete dash-file)))

      (testing "Directory components with dashes are NOT changed, only filename"
        (let [dashed-dir (io/file test-dir "my-cool-dir")
              _ (.mkdirs dashed-dir)
              underscore-file (io/file dashed-dir "my_file.clj")
              _ (spit underscore-file "(ns my-cool-dir.my-file)")
              dash-path (.getAbsolutePath (io/file dashed-dir "my-file.clj"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (= (.getCanonicalPath underscore-file) result))
          (is (str/includes? result "my-cool-dir")
              "Directory dashes should be preserved")
          (.delete underscore-file)
          (.delete dashed-dir)))

      (testing ".cljs extension works"
        (let [underscore-file (io/file test-dir "my_component.cljs")
              _ (spit underscore-file "(ns my-component)")
              dash-path (.getAbsolutePath (io/file test-dir "my-component.cljs"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (testing ".cljc extension works"
        (let [underscore-file (io/file test-dir "shared_utils.cljc")
              _ (spit underscore-file "(ns shared-utils)")
              dash-path (.getAbsolutePath (io/file test-dir "shared-utils.cljc"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (testing ".bb files are NOT corrected"
        (let [underscore-file (io/file test-dir "my_script.bb")
              _ (spit underscore-file "(println :hello)")
              dash-path (.getAbsolutePath (io/file test-dir "my-script.bb"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (not= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (testing "When neither dashed nor underscored file exists, returns original validated path"
        (let [dash-path (.getAbsolutePath (io/file test-dir "no-such-file.clj"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          ;; Should return the validated (but non-existent) path, not throw
          (is (string? result))
          (is (not (valid-paths/path-exists? result)))))

      (testing "Case-insensitive extension matching"
        (let [underscore-file (io/file test-dir "MY_THING.CLJ")
              _ (spit underscore-file "(ns my-thing)")
              dash-path (.getAbsolutePath (io/file test-dir "MY-THING.CLJ"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (testing "Reverse direction: underscore requested, only dash exists - should NOT correct"
        (let [dash-file (io/file test-dir "core-stuff.clj")
              _ (spit dash-file "(ns core-stuff)")
              underscore-path (.getAbsolutePath (io/file test-dir "core_stuff.clj"))
              result (valid-paths/validate-path-with-client underscore-path (make-client))]
          ;; Should return the underscore path (not found), not correct to dash
          (is (not= (.getCanonicalPath dash-file) result))
          (.delete dash-file)))

      (testing "Mixed dashes and underscores in filename - corrects all dashes"
        (let [underscore-file (io/file test-dir "my_cool_thing.clj")
              _ (spit underscore-file "(ns my-cool-thing)")
              dash-path (.getAbsolutePath (io/file test-dir "my-cool_thing.clj"))
              result (valid-paths/validate-path-with-client dash-path (make-client))]
          (is (= (.getCanonicalPath underscore-file) result))
          (.delete underscore-file)))

      (finally
        (when (.exists test-dir)
          (doseq [file (reverse (file-seq test-dir))]
            (.delete file)))))))

(deftest validate-bash-command-paths-test
  (let [test-dir (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir")))
        home-dir (System/getProperty "user.home")]

    (testing "Valid paths"
      (let [result (valid-paths/validate-bash-command-paths
                    "ls ."
                    test-dir
                    [test-dir])]
        (is (set? result))
        (is (contains? result test-dir))))

    (testing "Home directory expansion"
      (let [result (valid-paths/validate-bash-command-paths
                    "cat ~/.bashrc"
                    test-dir
                    [home-dir])]
        (is (contains? result (str home-dir "/.bashrc")))))

    (testing "Commands with no paths"
      (let [result (valid-paths/validate-bash-command-paths
                    "ps aux"
                    test-dir
                    [test-dir])]
        (is (= #{} result))))

    (testing "Invalid paths throw exceptions"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid paths in bash command"
           (valid-paths/validate-bash-command-paths
            "cat /etc/passwd"
            test-dir
            [test-dir]))))

    (testing "Mixed valid and invalid paths"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid paths in bash command"
           (valid-paths/validate-bash-command-paths
            "cp ./file /etc/passwd"
            test-dir
            [test-dir]))))

    (testing "Complex commands with quotes"
      (let [result (valid-paths/validate-bash-command-paths
                    "find . -name '*.txt' > \"./results with spaces.txt\""
                    test-dir
                    [test-dir])
            expected-file (str test-dir "/results with spaces.txt")]
        (is (contains? result test-dir))
        (is (contains? result expected-file))))

    (testing "Directory traversal attempts"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid paths in bash command"
           (valid-paths/validate-bash-command-paths
            "cat ../../../../etc/passwd"
            test-dir
            [test-dir]))))))
