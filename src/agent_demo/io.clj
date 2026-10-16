(ns agent-demo.io
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.pprint :as pp]
            [agent-demo.privacy :as privacy])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.file Files FileSystems]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn read-edn [path] (edn/read-string {:readers {} :default (fn [tag _] (throw (privacy/error "Tagged EDN is not supported" {:tag tag})))} (slurp path)))
(defn write-edn! [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data))))
(defn run! [& args]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args)) (.redirectErrorStream true)))
        output (slurp (.getInputStream p))
        code (.waitFor p)]
    (when-not (zero? code)
      (throw (privacy/error "External command failed; check local dependencies and input files" {:exit code})))
    output))
(defn duration [path]
  (Double/parseDouble
   (.trim (run! "ffprobe" "-v" "error" "-show_entries" "format=duration"
                "-of" "default=noprint_wrappers=1:nokey=1" path))))
(defn fresh-dir! [path]
  (let [f (io/file path)
        target (.toPath f)
        attributes (if (contains? (set (.supportedFileAttributeViews (FileSystems/getDefault))) "posix")
                     [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rwx------"))]
                     [])]
    (when (Files/exists target (make-array java.nio.file.LinkOption 0))
      (throw (privacy/error "Output directory already exists; choose a new version" {})))
    (when-let [parent (.getParent (.toAbsolutePath target))]
      (Files/createDirectories parent (make-array FileAttribute 0)))
    ;; Atomic leaf creation prevents overwriting an existing directory. On
    ;; POSIX the restrictive mode applies from creation, not after recording.
    (Files/createDirectory target (into-array FileAttribute attributes))
    f))
(defn binary! [& args]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args))
                   (.redirectError ProcessBuilder$Redirect/DISCARD)))
        bytes (.readAllBytes (.getInputStream p))]
    (when-not (zero? (.waitFor p)) (throw (privacy/error "Binary command failed; check local media dependencies and input files" {})))
    bytes))
