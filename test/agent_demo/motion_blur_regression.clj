(ns agent-demo.motion-blur-regression
  (:require [agent-demo.io :as dio] [agent-demo.render :as render]
            [agent-demo.speech :as speech] [agent-demo.cursor-regression :as pixels]
            [agent-demo.smoke :as smoke]))

(defn frame-difference [a b time]
  (let [read-frame #(dio/binary! "ffmpeg" "-v" "error" "-ss" (str time) "-i" %
                                  "-vf" "crop=640:120:0:0" "-frames:v" "1" "-pix_fmt" "gray" "-f" "rawvideo" "-")
        x (read-frame a) y (read-frame b)]
    (/ (reduce + (map #(abs (- (bit-and 255 %1) (bit-and 255 %2))) x y)) (double (count x)))))

(defn -main [& [out]]
  (dio/fresh-dir! out)
  (let [source (str out "/source.mkv") audio (str out "/tone.wav")
        capture {:version 1 :source source :width 640 :height 400
                 :scenes [{:id "a" :start 0 :end 1 :checkpoint? true}]
                 :cursor {:clock :source-video
                          :events [{:type :pointer :at 0 :x 100 :y 60}
                                   {:type :pointer :at 0.8 :x 420 :y 60}]}}
        project {:version 2 :cursor {:style :dot :size 8 :smoothing :linear}
                 :scenes [{:id "a" :source "a" :narration "Exposure test."}]}
        blur {:exposure (/ 1.0 30) :samples 8}]
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i"
              "color=black:s=640x416:r=30:d=1,drawbox=x=450:y=0:w=12:h=120:color=white:t=fill" "-c:v" "ffv1" source)
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i" "sine=frequency=440:duration=1.5" audio)
    (let [hash (smoke/sha256 source)]
      (with-redefs [speech/speech! (pixels/speech-stub audio 1.5)]
        (let [sharp (render/render! project capture (str out "/sharp"))
              blurred (render/render! (assoc project :motion-blur blur) capture (str out "/blur"))
              zero (render/render! (assoc project :motion-blur {:exposure 0}) capture (str out "/zero"))
              cut (render/render! (-> project (assoc :motion-blur blur)
                                       (assoc-in [:scenes 0 :cuts] [{:start 0.3 :end 0.6}])) capture (str out "/cut"))
              camera-project (-> project (assoc :cursor {:visible? false})
                                 (assoc-in [:scenes 0 :camera] [{:time 0 :zoom 1.6 :focus {:x 0.5 :y 0.5} :transition 0.7}]))
              camera-sharp (render/render! camera-project capture (str out "/camera-sharp"))
              camera-blur (render/render! (assoc camera-project :motion-blur blur) capture (str out "/camera-blur"))
              before (pixels/rgb sharp 0.5 288 58)
              after (pixels/rgb blurred 0.5 288 58)]
          (pixels/check! (pixels/dark? before) "Sharp cursor must not have a trailing pixel")
          (pixels/check! (every? #(< 50 %) after) "Exposure must produce a visible motion trail")
          (pixels/check! (pixels/dark? (pixels/rgb blurred 0.5 310 58)) "Exposure must not blur ahead of the cursor")
          (pixels/check! (pixels/bright? (pixels/rgb blurred 1.3 418 58)) "Held cursor must settle sharply")
          (pixels/check! (pixels/dark? (pixels/rgb blurred 1.3 405 58)) "Hold must not retain motion trails")
          (pixels/check! (= (smoke/sha256 sharp) (smoke/sha256 zero)) "Zero exposure must preserve the original render")
          (pixels/check! (= hash (smoke/sha256 source)) "Blur must not change source footage")
          (pixels/check! (pixels/bright? (pixels/rgb cut 0.3 338 58)) "First frame after cut must use the new pointer position")
          (pixels/check! (pixels/dark? (pixels/rgb cut 0.3 328 58)) "Cut boundary must not retain a pre-cut trail")
          (pixels/check! (> (frame-difference camera-sharp camera-blur 0.3) 0.3) "Camera movement must receive motion blur")
          (pixels/check! (< (frame-difference camera-sharp camera-blur 1.3) 0.5) "Stationary camera must settle without persistent blur")
          (dio/write-edn! (str out "/acceptance.edn")
                          {:sharp (smoke/verify-media! sharp [640 400])
                           :blur (smoke/verify-media! blurred [640 400])
                           :source-sha256 hash :unchanged-source true
                           :trail-pixels {:sharp before :blur after} :zero-exposure-identical true})))))
  (println "PASS: rendered exposure trail, settled holds, zero-exposure identity, unchanged source, media."))
