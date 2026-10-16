(ns agent-demo.edit-regression
  "Render reordered/reused source scenes without a browser or live speech service."
  (:require [agent-demo.core :as core] [agent-demo.io :as dio]
            [agent-demo.render :as render] [agent-demo.speech :as speech]
            [agent-demo.capture :as capture] [agent-demo.smoke :as smoke]))

(defn pixel [video time]
  (mapv #(bit-and 255 %)
        (dio/binary! "ffmpeg" "-v" "error" "-ss" (str time) "-i" video
                     "-vf" "crop=16:16:100:100,scale=1:1" "-frames:v" "1"
                     "-pix_fmt" "rgb24" "-f" "rawvideo" "-")))

(defn -main [& [out]]
  (core/require! (some? out) "Provide a fresh output directory" {})
  (dio/fresh-dir! out)
  (let [source (str out "/source.mkv") audio (str out "/speech.wav")
        recorded {:version 1 :source source :width 640 :height 400
                  :scenes [{:id "red" :start 0 :end 2 :checkpoint? true}
                           {:id "blue" :start 2 :end 4 :checkpoint? true}]}
        edit {:version 2 :scenes
              [{:id "demo" :source "blue" :narration "Blue first."
                :cuts [{:start 0.5 :end 1.5}]
                :camera [{:time 0 :zoom 1.25 :focus {:x 0.5 :y 0.5} :transition 0.2}]}
               {:id "explain" :source "red" :narration "Then red."}
               {:id "reprise" :source "blue" :narration "Blue again."
                :camera [{:time 0 :zoom 1 :transition 0.2}]}]}]
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i" "color=red:s=640x416:r=30:d=4"
              "-vf" "drawbox=color=blue:t=fill:enable='gte(t,2)'" "-c:v" "ffv1" source)
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i" "sine=frequency=440:duration=0.25" audio)
    (let [before (smoke/sha256 source)]
      (with-redefs [capture/capture! (fn [& _] (throw (Exception. "Render must not capture")))
                    speech/speech! (fn [project _]
                                     (into {} (for [scene (:scenes project)]
                                                [(:id scene) [{:text (:narration scene) :path audio :duration 0.25}]])))]
        (let [first-output (render/render! edit recorded (str out "/edit-v1"))
              revised (assoc edit :scenes [(second (:scenes edit)) (first (:scenes edit))])
              second-output (render/render! revised recorded (str out "/edit-v2"))
              first-plan (dio/read-edn (str out "/edit-v1/timeline.edn"))
              [r1 _ b1] (pixel first-output 0.25)
              [r2 _ b2] (pixel first-output 1.5)
              [r3 _ b3] (pixel first-output 3.5)
              [r4 _ b4] (pixel second-output 0.5)]
          (core/require! (and (< r1 30) (> b1 200) (> r2 200) (< b2 30)
                              (< r3 30) (> b3 200) (> r4 200) (< b4 30))
                         "Rendered source order/reuse did not match the edit" {})
          (core/require! (< (abs (- 5 (dio/duration first-output))) 0.1) "Cut duration was not applied" {})
          (core/require! (= ["blue" "red" "blue"] (mapv :source-id first-plan)) "Source references lost" {})
          (core/require! (= before (smoke/sha256 source)) "Source footage changed during editing" {})
          (dio/write-edn! (str out "/acceptance.edn")
                          {:initial (smoke/verify-media! first-output [640 400])
                           :revised (smoke/verify-media! second-output [640 400])
                           :speech :synthetic-tone :source-sha256 before
                           :unchanged-source true :order-and-reuse :passed :cuts :passed})
          (println "PASS: source order, reuse, omission, local camera, cuts, media decoding, and unchanged footage."))))))
