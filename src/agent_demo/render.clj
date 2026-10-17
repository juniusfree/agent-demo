(ns agent-demo.render
  (:require [agent-demo.core :as core] [agent-demo.camera :as camera] [agent-demo.io :as dio] [agent-demo.speech :as speech]
            [agent-demo.motion-blur :as blur] [agent-demo.privacy :as privacy] [agent-demo.cursor-render :as cursor-render] [clojure.java.io :as io] [clojure.string :as str])
  (:import [java.awt Color Font RenderingHints] [java.awt.image BufferedImage] [javax.imageio ImageIO]))

(defn caption! [text path width height]
  (let [img (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics img)
        scale (min (/ width 1280.0) (/ height 800.0))
        width (int (/ width scale)) height (int (/ height scale))]
    (try
      (.scale g scale scale)
      (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
      (.setFont g (Font. "SansSerif" Font/PLAIN 30))
      (let [metrics (.getFontMetrics g)
            lines (reduce (fn [lines word]
                            (let [current (peek lines) candidate (str current (when (seq current) " ") word)]
                              (if (< (.stringWidth metrics candidate) (- width 100))
                                (conj (pop lines) candidate)
                                (conj lines word))))
                          [""] (str/split text #"\s+"))
            _ (core/require! (<= (count lines) 2) "Caption exceeds two lines; shorten narration wording" {:text text})
            box-width (+ 32 (apply max (map #(.stringWidth metrics %) lines)))
            box-height (+ 20 (* 38 (count lines)))
            x (quot (- width box-width) 2)
            y (- height 110 box-height)]
        (.setColor g (Color. 15 18 24 220))
        (.fillRoundRect g x y box-width box-height 12 12)
        (.setColor g Color/WHITE)
        (doseq [[i line] (map-indexed vector lines)]
          (.drawString g line (int (+ x 16)) (int (+ y 34 (* i 38))))))
      (ImageIO/write img "png" (io/file path))
      (finally (.dispose g)))))
(defn concat-file! [path files]
  ;; FFmpeg concat syntax, not shell syntax.
  (spit path (str/join "\n" (map #(str "file '" (str/replace (.getCanonicalPath (io/file %)) "'" "'\\''") "'") files))))
(defn render-segment! [source segment out width height]
  (let [sampling (blur/settings segment)
        id (:id segment) audio (str out "/" id "-audio.wav")
        listing (str out "/" id "-audio.txt")
        video (str out "/" id "-scene.mp4")
        captions (mapv (fn [i cue]
                         (let [path (str out "/" id "-caption-" i ".png")]
                           (caption! (:text cue) path width height)
                           (assoc cue :image path)))
                       (range) (:cues segment))
        images (mapv :image captions)
        cursor-layer (when (seq (get-in segment [:cursor :events]))
                       (cursor-render/layer! segment (str out "/" id "-cursor.mov") width height))
        base (str "[0:v]crop=" width ":" height ":0:0,setpts=PTS-STARTPTS,fps=30,"
                  (core/cut-filter (:cuts segment))
                  ",tpad=stop_mode=clone:stop_duration=" (:hold segment)
                  ",trim=duration=" (:duration segment) ",setpts=PTS-STARTPTS"
                  (when sampling (str "," (blur/repeat-filter segment width height))) "[footage]")
        presentation (str (if cursor-layer
                            (str "[footage][" (+ 2 (count images)) ":v]overlay=0:0:format=auto:shortest=1,")
                            "[footage]")
                          (camera/filter-expression segment width height
                                                    (if sampling {:fps (* 30 (:samples sampling))
                                                                  :time-expression (blur/time-expression segment)} {}))
                          (when sampling (str "," (blur/resolve-filter segment))) "[v0]")
        overlays (map-indexed
                  (fn [i cue]
                    (format "[v%d][%d:v]overlay=0:0:enable='gte(t,%.6f)*lt(t,%.6f)'[v%d]"
                            i (+ i 2) (double (:start cue)) (double (:end cue)) (inc i)))
                  captions)
        n (count captions)
        final-label (str "v" n)
        filters (into [base presentation] overlays)]
    (concat-file! listing (map :path (:cues segment)))
    (dio/run! "ffmpeg" "-v" "error" "-y" "-f" "concat" "-safe" "0" "-i" listing
              "-c:a" "pcm_s16le" audio)
    (apply dio/run!
           (concat ["ffmpeg" "-v" "error" "-y"
                    "-ss" (str (:source-start segment)) "-t" (str (:source-duration segment)) "-i" source
                    "-i" audio]
                   (mapcat #(vector "-loop" "1" "-framerate" "30" "-i" %) images)
                   (when cursor-layer ["-i" cursor-layer])
                   ["-filter_complex" (str/join ";" filters)
                    "-map" (str "[" final-label "]") "-map" "1:a"
                    "-af" "apad" "-t" (str (:duration segment))
                    "-c:v" "libx264" "-preset" "fast" "-crf" "20" "-pix_fmt" "yuv420p"
                    "-c:a" "aac" "-ar" "48000" "-movflags" "+faststart" video]))
    video))

(defn render! [project capture out]
  (core/validate-edit project)
  (core/validate-capture project capture)
  (core/require! (.isFile (io/file (:source capture))) "Source recording is missing" {})
  (let [source-duration (dio/duration (:source capture))]
    (core/require! (every? #(<= (:end %) (+ source-duration 0.05)) (:scenes capture))
                   "Captured scene extends beyond the source recording" {}))
  (dio/fresh-dir! out)
  (let [speech (speech/speech! project out)
        segments (core/plan project capture speech)
        videos (mapv #(render-segment! (:source capture) % out (:width capture) (:height capture)) segments)
        listing (str out "/segments.txt")
        output (str out "/demo.mp4")]
    (dio/write-edn! (str out "/timeline.edn") segments)
    (concat-file! listing videos)
    (dio/run! "ffmpeg" "-v" "error" "-y" "-f" "concat" "-safe" "0" "-i" listing
              "-c" "copy" "-movflags" "+faststart" output)
    (dio/write-edn! (str out "/project.edn") (privacy/project-copy project))
    (dio/write-edn! (str out "/verification.edn")
                    {:status :rendered :duration (dio/duration output)
                     :human-review-required [:framing :caption-clearance :claims :private-data]
                     :source (:source capture)})
    output))
