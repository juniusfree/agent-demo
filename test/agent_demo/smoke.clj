(ns agent-demo.smoke
  (:require [agent-demo.main :as main] [agent-demo.io :as dio] [agent-demo.core :as core]
            [agent-demo.capture :as capture] [agent-demo.render :as render]
            [clojure.java.io :as io] [clojure.data.json :as json])
  (:import [java.security MessageDigest] [java.util HexFormat]))

(defn sha256 [path]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256")
                                    (java.nio.file.Files/readAllBytes (.toPath (io/file path))))))
(defn check! [condition message] (core/require! condition message {}))
(defn probe [path]
  (json/read-str (dio/run! "ffprobe" "-v" "error" "-show_streams" "-of" "json" path)
                 :key-fn keyword))
(defn verify-media! [file dimensions]
  (let [streams (:streams (probe file))
        video (first (filter #(= "video" (:codec_type %)) streams))
        audio (first (filter #(= "audio" (:codec_type %)) streams))]
    (check! (= "h264" (:codec_name video)) "Missing H.264 video")
    (check! (= "aac" (:codec_name audio)) "Missing AAC audio")
    (check! (= dimensions ((juxt :width :height) video)) "Wrong output dimensions")
    (check! (< (abs (- (Double/parseDouble (:duration video))
                      (Double/parseDouble (:duration audio)))) 0.1) "Audio/video duration mismatch")
    (dio/run! "ffmpeg" "-v" "error" "-i" file "-f" "null" "-")
    {:video-duration (:duration video) :audio-duration (:duration audio)}))

(defn marker-regression! [out]
  (let [source (str out "/markers.mp4") wav (str out "/silence.wav")]
    (dio/run! "ffmpeg" "-v" "error" "-y" "-f" "lavfi" "-i" "color=white:s=1280x816:r=30:d=3"
              "-vf" (str "drawbox=x=0:y=0:w=iw:h=ih:color=red:t=fill:enable='gte(t,1)',"
                         "drawbox=x=0:y=800:w=iw:h=16:color=0x2c10f0:t=fill,"
                         "drawbox=x=0:y=800:w=iw:h=16:color=0x4810f0:t=fill:enable='gte(t,1.5)'")
              "-c:v" "libx264" "-pix_fmt" "yuv420p" source)
    (let [marks (capture/boundaries source 800 [1 2])]
      (check! (< (abs (- 1.5 (marks 2))) 0.04) "Marker boundary drift")
      (dio/run! "ffmpeg" "-v" "error" "-y" "-f" "lavfi" "-i" "anullsrc=r=48000:cl=mono"
                "-t" "2.5" wav)
      (let [segment {:id "regression" :source-start (marks 1) :source-duration (- (marks 2) (marks 1))
                     :hold 1.0 :duration 2.5 :camera []
                     :cues [{:text "Regression check." :path wav :start 0 :end 2.5 :duration 2.5}]}
            video (render/render-segment! source segment out 1280 800)
            rgb (mapv #(bit-and % 255)
                      (dio/binary! "ffmpeg" "-v" "error" "-ss" "2" "-i" video "-frames:v" "1"
                                   "-vf" "crop=16:16:600:300,scale=1:1"
                                   "-pix_fmt" "rgb24" "-f" "rawvideo" "-"))]
        (check! (and (> (rgb 0) 200) (< (rgb 1) 30) (< (rgb 2) 30))
                "Checkpoint hold froze before the completed UI state")))))

(defn -main [& [directory]]
  (let [out (or directory (str "work/smoke-" (System/currentTimeMillis)))
        _ (dio/fresh-dir! out)
        server (main/fixture! 0)
        port (.getPort (.getAddress server))]
    (try
      (marker-regression! out)
      (let [project (assoc-in (dio/read-edn "examples/fixture.edn")
                              [:scenes 0 :actions 0 :url] (str "http://127.0.0.1:" port))
            recorded (capture/capture! project (str out "/capture"))
            before (sha256 (:source recorded))
            first-output (render/render! project recorded (str out "/render-v1"))
            revised (-> project
                        (assoc :camera [{:at {:scene "name" :time 0.4} :zoom 1.35
                                         :focus (select-keys (get-in recorded [:scenes 0 :focus]) [:x :y])
                                         :transition 0.6}
                                        {:at {:scene "save" :time 0.5} :zoom 1.0 :transition 0.6}])
                        (assoc-in [:scenes 0 :narration]
                                  "Give this project a recognizable name. Reviewers can now distinguish this example from other projects without inspecting the implementation."))
            second-output (render/render! revised recorded (str out "/render-v2"))
            first-timeline (dio/read-edn (str out "/render-v1/timeline.edn"))
            second-timeline (dio/read-edn (str out "/render-v2/timeline.edn"))
            result {:source-sha256 before
                    :initial (verify-media! first-output ((juxt :width :height) recorded))
                    :revised (verify-media! second-output ((juxt :width :height) recorded))
                    :unchanged-source (= before (sha256 (:source recorded)))
                    :checkpoint-regression :passed
                    :live-kokoro :reused
                    :human-review [:listen-to-narration :inspect-framing-and-caption-clearance]}]
        (check! (every? #(empty? (:camera %)) first-timeline) "Zoom must default off")
        (check! (= 1.35 (get-in second-timeline [0 :camera 0 :to :zoom])) "Explicit zoom was not enabled")
        (check! (:unchanged-source result) "Re-render modified source footage")
        (check! (> (:hold (first second-timeline)) (:hold (first first-timeline)))
                "Longer narration did not extend checkpoint")
        (doseq [[i segment] (map-indexed vector second-timeline)]
          (dio/run! "ffmpeg" "-v" "error" "-y" "-ss"
                    (str (+ (:start segment) (* 0.7 (:duration segment)))) "-i" second-output
                    "-frames:v" "1" (str out "/scene-" i ".png")))
        (dio/write-edn! (str out "/acceptance.edn") result)
        (println (str "PASS: " out "/acceptance.edn"))
        (println second-output))
      (finally (.stop server 0)))))
