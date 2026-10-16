(ns agent-demo.cut-regression
  (:require [agent-demo.core :as core] [agent-demo.io :as dio]
            [agent-demo.render :as render]))

(defn -main [& [out]]
  (core/require! (some? out) "Provide a fresh output directory" {})
  (dio/fresh-dir! out)
  (let [source (str out "/source.mkv") audio (str out "/speech.wav")
        actions [{:op :checkpoint}]
        project {:version 1 :scenes [{:id "cut" :narration "Done." :actions actions
                                     :cuts [{:start 1 :end 2}]}]}
        capture {:version 1 :source source :width 1280 :height 800
                 :scenes [{:id "cut" :start 0 :end 3 :checkpoint? true :actions actions}]}]
    ;; Red = action, blue = unwanted wait, green = completed result.
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i" "color=red:s=1280x816:r=30:d=3"
              "-vf" "drawbox=color=blue:t=fill:enable='gte(t,1)*lt(t,2)',drawbox=color=lime:t=fill:enable='gte(t,2)'"
              "-c:v" "ffv1" source)
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i" "sine=frequency=440:duration=0.5" audio)
    (let [segment (first (core/plan project capture {"cut" [{:text "Done." :path audio :duration 0.5}]}))
          video (render/render-segment! source segment out 1280 800)
          pixel (fn [time]
                  (mapv #(bit-and 255 %)
                        (dio/binary! "ffmpeg" "-v" "error" "-ss" (str time) "-i" video
                                     "-vf" "crop=16:16:600:300,scale=1:1"
                                     "-frames:v" "1" "-pix_fmt" "rgb24" "-f" "rawvideo" "-")))
          early (pixel 0.5) late (pixel 1.5)]
      (core/require! (< (Math/abs (- 2 (dio/duration video))) 0.1) "Cut did not shorten output" {})
      (core/require! (and (> (early 0) 200) (< (early 1) 30)) "Action was lost" {:rgb early})
      (core/require! (and (< (late 0) 30) (> (late 1) 200) (< (late 2) 30))
                     "Wait was not removed or completed result was lost" {:rgb late})
      (dio/write-edn! (str out "/timeline.edn") [segment])
      (println "PASS: action retained, wait removed, completed result retained; output is 2 seconds."))))
