(ns agent-demo.cursor-regression
  "Real capture and rendered-pixel checks; local synthetic audio, no Kokoro."
  (:require [agent-demo.capture :as capture] [agent-demo.core :as core]
            [agent-demo.io :as dio] [agent-demo.render :as render]
            [agent-demo.speech :as speech] [agent-demo.smoke :as smoke]
            [agent-demo.cursor :as cursor])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(defn rgb [video time x y]
  (mapv #(bit-and 255 %)
        (dio/binary! "ffmpeg" "-v" "error" "-ss" (str time) "-i" video
                     "-vf" (str "crop=4:4:" x ":" y ",scale=1:1") "-frames:v" "1"
                     "-pix_fmt" "rgb24" "-f" "rawvideo" "-")))
(defn bright? [rgb] (and (= 3 (count rgb)) (every? #(< 200 %) rgb)))
(defn dark? [rgb] (and (= 3 (count rgb)) (every? #(< % 30) rgb)))
(defn check! [ok message] (core/require! ok message {}))
(defn speech-stub [audio duration]
  (fn [project _]
    (into {} (for [scene (:scenes project)]
               [(:id scene) [{:text (:narration scene) :path audio :duration duration}]]))))

(defn synthetic! [out]
  (let [source (str out "/source.mkv") audio (str out "/tone.wav")
        recorded {:version 1 :source source :width 640 :height 400
                  :scenes [{:id "a" :start 0 :end 2 :checkpoint? true}
                           {:id "b" :start 2 :end 4 :checkpoint? true}]
                  :cursor {:clock :source-video
                           :events [{:type :pointer :at 0 :x 100 :y 100}
                                    {:type :pointer :at 0.5 :x 100 :y 100}
                                    {:type :pointer :at 1 :x 300 :y 100}
                                    {:type :pointer :at 2.5 :x 300 :y 100}
                                    {:type :pointer :at 3 :x 400 :y 100}
                                    {:type :click :at 3.25 :x 400 :y 100}]}}
        project {:version 2 :cursor {:style :dot :size 20}
                 :scenes [{:id "first" :source "b" :narration "Blue."
                           :cuts [{:start 0.5 :end 1}]}
                          {:id "second" :source "a" :narration "Red."
                           :camera [{:time 0 :zoom 2 :focus {:x 0.5 :y 0.5} :transition 0}]}
                          {:id "replay" :source "b" :narration "Again."
                           :camera [{:time 0 :zoom 1 :transition 0}]}]}]
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i" "color=black:s=640x416:r=30:d=4" "-c:v" "ffv1" source)
    (dio/run! "ffmpeg" "-v" "error" "-f" "lavfi" "-i" "sine=frequency=440:duration=2" audio)
    (let [hash (smoke/sha256 source)]
      (with-redefs [speech/speech! (speech-stub audio 2)]
        (let [shown (render/render! project recorded (str out "/shown"))
              hidden (render/render! (assoc project :cursor {:visible? false}) recorded (str out "/hidden"))
              restyled (render/render! (assoc project :cursor {:style :arrow :size 60 :clicks? false})
                                       recorded (str out "/restyled"))
              timeline (dio/read-edn (str out "/shown/timeline.edn"))
              third (:start (nth timeline 2))]
          (check! (dark? (rgb source 2.25 300 100)) "Source must have no cursor")
          (check! (bright? (rgb shown 0.25 300 100)) "Reordered cursor starts at source B position")
          (check! (bright? (rgb shown 0.6 400 100)) "Cursor jumps over cut with source footage")
          (check! (bright? (rgb shown 2.1 400 100)) "Checkpoint holds retain final pointer")
          (check! (bright? (rgb shown (+ third 0.25) 300 100)) "Reused footage replays its source cursor")
          ;; Second source at local 1.25 has x=300,y=100. Zoom 2 around center
          ;; maps that to x=280,y=0; sample inside the clipped dot, not its edge.
          (check! (bright? (rgb shown (+ (:start (second timeline)) 1.25) 280 2))
                  "Cursor must share the camera's coordinate transform")
          (check! (dark? (rgb hidden 0.25 300 100)) "Hidden cursor must leave clean footage")
          (check! (dark? (rgb shown 0.25 310 120)) "Small dot should not cover larger arrow area")
          (check! (bright? (rgb restyled 0.25 310 120)) "Style and size must change without recapture")
          (check! (= hash (smoke/sha256 source)) "Cursor edits changed source bytes")
          ;; Ring color exists only in the editable click layer, then expires.
          (let [[r g b] (rgb shown 1.0 418 98)]
            (check! (> b (+ r 30)) "Retained click must produce a blue pulse"))
          (check! (dark? (rgb restyled 1.0 418 98)) "Disabled click effects must disappear")
          (check! (dark? (rgb shown 2.1 420 100)) "Click pulse must expire during hold")
          (dio/write-edn! (str out "/synthetic-acceptance.edn")
                          {:shown (smoke/verify-media! shown [640 400])
                           :hidden (smoke/verify-media! hidden [640 400])
                           :restyled (smoke/verify-media! restyled [640 400])
                           :source-sha256 hash :unchanged-source true :pixel-checks :passed}))))))

(defn browser! [out]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        html (str "<!doctype html><style>body{margin:0;background:black}button{position:absolute;left:280px;top:180px;width:80px;height:40px;cursor:pointer}</style>"
                  "<button id='go' disabled onclick=\"document.body.style.background='red'\">Go</button>"
                  "<script>setTimeout(()=>{const b=document.querySelector('#go');b.style.left='340px';b.disabled=false},1200)</script>")]
    (.createContext server "/" (reify HttpHandler
                                  (handle [_ exchange]
                                    (let [bytes (.getBytes html "UTF-8")]
                                      (.sendResponseHeaders exchange 200 (alength bytes))
                                      (with-open [stream (.getResponseBody exchange)] (.write stream bytes))))))
    (.start server)
    (try
      (let [project {:version 2 :viewport {:width 640 :height 400}
                     :capture {:scenes [{:id "go" :actions [{:op :goto :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}
                                                           {:op :click :selector "#go"} {:op :checkpoint}]}]}}
            recorded (capture/capture! project (str out "/browser-capture"))
            _ (cursor/validate-events recorded)
            click (first (filter #(= :click (:type %)) (get-in recorded [:cursor :events])))
            time (:at click)
            pixel (rgb (:source recorded) (+ time 0.15) 0 0)]
        (check! (= :source-video (:event-clock recorded)) "Capture must write video-time events")
        (check! (and click (< (abs (- 380 (:x click))) 1) (< (abs (- 200 (:y click))) 1)) "Click coordinates missing")
        (check! (= (select-keys click [:x :y])
                   (cursor/position-at (get-in recorded [:cursor :events]) time :smooth))
                "Cursor must follow actual click location after layout changes")
        (check! (and (> (first pixel) 200) (< (second pixel) 30)) "Video-clock click did not align with browser change")
        (check! (dark? (rgb (:source recorded) (- time 0.15) 0 0)) "Pulse must not precede the actionability wait")
        ;; Before movement begins, the initial cursor must not be drawn into
        ;; the raw video. Inspect a white-arrow interior from the old overlay.
        (check! (dark? (rgb (:source recorded) (:start (first (:scenes recorded))) 106 110))
                "Capture still contains baked-in pointer pixels")
        (dio/write-edn! (str out "/browser-acceptance.edn")
                        {:clean-source :passed :source-clock-click :passed :click click
                         :clock-anchors (:clock-anchors recorded)}))
      (finally (.stop server 0)))))

(defn -main [& [out]]
  (check! (some? out) "Provide a fresh output directory")
  (dio/fresh-dir! out)
  (synthetic! out)
  (browser! out)
  (println "PASS: clean capture, source clock, editable style/size/visibility/clicks, cuts, reorder/reuse, camera, holds, media, unchanged source."))
