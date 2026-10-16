(ns agent-demo.io
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.pprint :as pp])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn read-edn [path] (edn/read-string {:readers {} :default (fn [tag _] (throw (ex-info "Tagged EDN is not supported" {:tag tag})))} (slurp path)))
(defn write-edn! [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data))))
(defn run! [& args]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args)) (.redirectErrorStream true)))
        output (slurp (.getInputStream p))
        code (.waitFor p)]
    (when-not (zero? code)
      (throw (ex-info "External command failed" {:command (vec args) :exit code :output output})))
    output))
(defn duration [path]
  (Double/parseDouble
   (.trim (run! "ffprobe" "-v" "error" "-show_entries" "format=duration"
                "-of" "default=noprint_wrappers=1:nokey=1" path))))
(defn fresh-dir! [path]
  (let [f (io/file path)]
    (when (.exists f) (throw (ex-info "Output directory already exists; choose a new version" {:path (str f)})))
    (when-not (.mkdirs f) (throw (ex-info "Cannot create directory" {:path (str f)})))
    f))
(defn binary! [& args]
  (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args))
                   (.redirectError ProcessBuilder$Redirect/INHERIT)))
        bytes (.readAllBytes (.getInputStream p))]
    (when-not (zero? (.waitFor p)) (throw (ex-info "Binary command failed" {:command args})))
    bytes))
