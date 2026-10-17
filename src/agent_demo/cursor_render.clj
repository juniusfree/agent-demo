(ns agent-demo.cursor-render
  "Rasterization/encoder boundary for the pure cursor timeline."
  (:require [agent-demo.cursor :as cursor] [agent-demo.motion-blur :as blur] [agent-demo.privacy :as privacy])
  (:import [java.awt AlphaComposite BasicStroke Color RenderingHints]
           [java.awt.geom Path2D$Double Ellipse2D$Double]
           [java.awt.image BufferedImage DataBufferByte]
           [java.io BufferedOutputStream]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn paint! [image {:keys [position settings clicks]}]
  (let [g (.createGraphics ^BufferedImage image)]
    (try
      (.setComposite g AlphaComposite/Clear)
      (.fillRect g 0 0 (.getWidth image) (.getHeight image))
      (.setComposite g AlphaComposite/SrcOver)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (doseq [{:keys [x y age]} clicks]
        (let [r (* (:size settings) (+ 0.35 (* 1.3 (/ age 0.5))))]
          (.setColor g (Color. 66 133 255 (int (* 210 (- 1 (/ age 0.5))))))
          (.setStroke g (BasicStroke. (float (max 1 (/ (:size settings) 10)))))
          (.draw g (Ellipse2D$Double. (- x r) (- y r) (* 2 r) (* 2 r)))))
      (when (and position (:visible? settings))
        (.translate g (double (:x position)) (double (:y position)))
        (.scale g (/ (:size settings) 30.0) (/ (:size settings) 30.0))
        (let [shape (if (= :dot (:style settings))
                      (Ellipse2D$Double. -15 -15 30 30)
                      (doto (Path2D$Double.)
                        (.moveTo 0 0) (.lineTo 0 22) (.lineTo 6 16)
                        (.lineTo 11 26) (.lineTo 16 23) (.lineTo 11 14)
                        (.lineTo 20 14) (.closePath)))]
          (.setColor g Color/WHITE)
          (.fill g shape)
          (.setColor g (Color. 34 34 34))
          (.setStroke g (BasicStroke. 2.0 BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
          (.draw g shape)))
      image
      (finally (.dispose g)))))

(defn layer!
  "Stream reusable ABGR frames into a lossless alpha movie; no PNG frame pile."
  [segment path width height]
  (let [sampling (blur/settings segment)
        samples (get sampling :samples 1)
        image (BufferedImage. width height BufferedImage/TYPE_4BYTE_ABGR)
        bytes (.getData ^DataBufferByte (.getDataBuffer (.getRaster image)))
        args ["ffmpeg" "-v" "error" "-y" "-f" "rawvideo" "-pixel_format" "abgr"
              "-video_size" (str width "x" height) "-framerate" (str (* 30 samples)) "-i" "pipe:0"
              "-an" "-c:v" "qtrle" "-pix_fmt" "argb" "-threads" "1" path]
        process (.start (doto (ProcessBuilder. ^java.util.List args)
                          (.redirectError ProcessBuilder$Redirect/DISCARD)
                          (.redirectOutput ProcessBuilder$Redirect/DISCARD)))]
    (try
      (with-open [stream (BufferedOutputStream. (.getOutputStream process) 1048576)]
        (dotimes [frame (long (Math/ceil (* 30 (:duration segment))))]
          (dotimes [sample samples]
            (paint! image (cursor/frame-state segment
                                             (if sampling (blur/sample-time segment frame sample) (/ frame 30.0))
                                             (boolean sampling)))
            (.write stream bytes))))
      (when-not (zero? (.waitFor process))
        (throw (privacy/error "Cursor layer encoding failed; check local FFmpeg" {})))
      path
      (catch java.io.IOException _
        (throw (privacy/error "Cursor layer encoding failed; check local FFmpeg" {})))
      (finally
        (when (.isAlive process) (.destroyForcibly process))
        (.flush image)))))
