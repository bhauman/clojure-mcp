(ns clojure-mcp.daemon.state
  "The daemon's files under ~/.cache/clojure-mcp/daemons/<version>/: `token`
   (read only by the shim), `pid`, and `port`, written last and atomically so
   that its presence means the daemon is accepting connections."
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.nio.channels FileChannel OverlappingFileLockException)
           (java.nio.file CopyOption Files OpenOption StandardCopyOption StandardOpenOption)
           (java.nio.file.attribute PosixFilePermissions)
           (java.security SecureRandom)))

(defn default-root []
  (let [cache (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                  (str (System/getProperty "user.home") "/.cache"))]
    (str cache "/clojure-mcp/daemons")))

(defn state-dir [root version]
  (str (io/file root version)))

(defn- chmod! [f perms]
  (Files/setPosixFilePermissions (.toPath (io/file f)) (PosixFilePermissions/fromString perms)))

(defn ensure-dir! [dir]
  (.mkdirs (io/file dir))
  (chmod! dir "rwx------"))

(defn lock!
  "Takes `daemon.lock` for the life of this daemon, or returns nil when another
   daemon of this version holds it. The OS drops it when the process dies."
  [dir]
  (let [ch (FileChannel/open (.toPath (io/file dir "daemon.lock"))
                             (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    (or (try (.tryLock ch) (catch OverlappingFileLockException _ nil))
        (do (.close ch) nil))))

(defn write-token!
  "Writes a fresh random token, readable by this user only, and returns it."
  [dir]
  (let [bytes (byte-array 32)
        _ (.nextBytes (SecureRandom.) bytes)
        token (string/join (map #(format "%02x" %) bytes))
        f (io/file dir "token")]
    (spit f "")
    (chmod! f "rw-------")
    (spit f token)
    token))

(defn write-ready!
  "Writes `pid`, then `port` through a temp file and an atomic rename."
  [dir {:keys [port pid]}]
  (spit (io/file dir "pid") (str pid))
  (let [tmp (io/file dir "port.tmp")]
    (spit tmp (str port))
    (Files/move (.toPath tmp) (.toPath (io/file dir "port"))
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn clear-port!
  "Deletes `port` only if it still names `port`: a newer daemon of the same
   version may already own the file."
  [dir port]
  (let [f (io/file dir "port")]
    (when (and (.exists f) (= (str port) (string/trim (slurp f))))
      (.delete f))))
