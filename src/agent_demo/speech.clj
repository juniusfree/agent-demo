(ns agent-demo.speech
  (:require [agent-demo.core :as core] [agent-demo.io :as dio]
            [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str])
  (:import [java.net URI] [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration] [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.util.concurrent TimeUnit]))

(def client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 3)) .build))
(defn endpoint [config]
  (let [s (str/replace (or (:endpoint config) "http://127.0.0.1:8880") #"/$" "")
        uri (URI/create s)]
    (core/require! (and (= "http" (.getScheme uri))
                        (#{"127.0.0.1" "localhost" "[::1]"} (.getHost uri))
                        (nil? (.getUserInfo uri)) (nil? (.getQuery uri)) (nil? (.getFragment uri))
                        (str/blank? (.getPath uri)))
                   "Kokoro endpoint must be a loopback HTTP origin without credentials or path" {})
    s))
(defn healthy? [origin]
  (try
    (let [req (-> (HttpRequest/newBuilder (URI/create (str origin "/health")))
                  (.timeout (Duration/ofSeconds 2)) .GET .build)
          response (.send client req (HttpResponse$BodyHandlers/ofString))]
      (and (= 200 (.statusCode response))
           (= "ok" (get (json/read-str (.body response)) "status"))))
    (catch Exception _ false)))
(defn stop! [^Process process]
  (let [children (.toList (.descendants process))]
    (doseq [child (reverse (vec children))] (.destroy child))
    (.destroy process)
    (when-not (.waitFor process 3 TimeUnit/SECONDS) (.destroyForcibly process))
    (doseq [child children :when (.isAlive child)] (.destroyForcibly child))))
(defn launch! [command logfile]
  (core/require! (and (vector? command) (seq command) (every? string? command))
                 "Kokoro unavailable: configure :kokoro :command as a vector for a preinstalled service" {})
  (.start (doto (ProcessBuilder. ^java.util.List command)
            (.redirectErrorStream true)
            (.redirectOutput (ProcessBuilder$Redirect/appendTo (io/file logfile))))))
(defn with-service
  "Only owns processes returned by launch-fn; injectable boundaries support lifecycle tests."
  [config logfile f & [{:keys [ready? launch-fn stop-fn alive? sleep-fn clock]
                        :or {ready? healthy? launch-fn launch! stop-fn stop!
                             alive? #(.isAlive ^Process %) sleep-fn #(Thread/sleep %)
                             clock #(System/currentTimeMillis)}}]]
  (let [origin (endpoint config)]
    (if (ready? origin)
      (f origin)
      (let [process (launch-fn (:command config) logfile)]
        (try
          (let [deadline (+ (clock) (or (:startup-timeout-ms config) 60000))]
            (loop []
              (core/require! (alive? process) "Kokoro process exited during startup; inspect kokoro.log" {})
              (when-not (ready? origin)
                (core/require! (< (clock) deadline) "Kokoro startup timed out; inspect kokoro.log" {})
                (sleep-fn 200)
                (recur)))
            (f origin))
          (finally (stop-fn process)))))))
(defn generate! [origin config text path]
  (let [payload (json/write-str {:model "kokoro" :voice (or (:voice config) "af_heart")
                                 :input text :speed 0.95 :response_format "wav"})
        request (-> (HttpRequest/newBuilder (URI/create (str origin "/v1/audio/speech")))
                    (.timeout (Duration/ofMinutes 2))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString payload)) .build)
        response (.send client request (HttpResponse$BodyHandlers/ofByteArray))
        raw (str path ".raw.wav")]
    (core/require! (= 200 (.statusCode response)) "Kokoro speech request failed" {:status (.statusCode response)})
    (with-open [out (io/output-stream raw)] (.write out ^bytes (.body response)))
    (dio/run! "ffmpeg" "-v" "error" "-y" "-i" raw "-af" "atempo=1.25" "-ar" "48000" "-ac" "1" path)
    (io/delete-file raw)
    {:text text :path (.getCanonicalPath (io/file path)) :duration (dio/duration path)}))
(defn speech! [project out]
  (with-service
    (:kokoro project) (str out "/kokoro.log")
    (fn [origin]
      (into {}
            (for [scene (:scenes project)]
              [(:id scene)
               (mapv (fn [i text]
                       (generate! origin (:kokoro project) text
                                  (str out "/" (:id scene) "-" i ".wav")))
                     (range) (core/cues (:narration scene)))])))))
