(ns agent-demo.motion-blur
  "Independent exposure sampling. Never averages across output-frame groups."
  (:require [agent-demo.privacy :as privacy] [clojure.string :as str]))

(def defaults {:exposure (/ 1.0 120) :samples 8})
(defn validate [project]
  (when (contains? project :motion-blur)
    (let [settings (:motion-blur project)]
      (when-not (and (map? settings) (every? #{:exposure :samples} (keys settings)))
        (throw (privacy/error "Motion blur needs a map with :exposure and :samples" {})))
      (let [{:keys [exposure samples]} (merge defaults settings)]
        (when-not (and (number? exposure) (Double/isFinite (double exposure)) (<= 0 exposure (/ 1.0 30)))
          (throw (privacy/error "Motion blur :exposure must be between 0 and 1/30 seconds" {})))
        (when-not (and (integer? samples) (<= 2 samples 16))
          (throw (privacy/error "Motion blur :samples must be an integer between 2 and 16" {}))))))
  project)

(defn settings [segment]
  (when-let [value (:motion-blur segment)]
    (let [value (merge defaults value)] (when (pos? (:exposure value)) value))))

(defn boundaries
  "Edited-local cut boundaries and discontinuous presentation changes. Scene
  starts always reset exposure, including reordered/reused source instances."
  [segment]
  (sort (distinct
         (concat [0.0]
                 (second (reduce (fn [[removed times] {:keys [start end]}]
                                   [(+ removed (- end start)) (conj times (- start removed))])
                                 [0 []] (:cuts segment)))
                 (for [{:keys [start duration]} (:camera segment) :when (zero? duration)
                       :let [local (- start (:start segment))] :when (<= 0 local)] local)
                 (for [{:keys [start]} (get-in segment [:cursor :track])
                       :let [local (- start (:start segment))] :when (<= 0 local)] local)))))

(defn sample-time [segment frame sample]
  (let [{:keys [exposure samples]} (settings segment)
        time (/ frame 30.0)
        lower (last (take-while #(<= % (+ time 1.0e-8)) (boundaries segment)))]
    (max lower (- time (* exposure (- 1 (/ (+ sample 0.5) samples)))))))

(defn- decimal [x] (String/format java.util.Locale/ROOT "%.12f" (to-array [(double x)])))
(defn time-expression
  "Same sampling clock as sample-time, expressed for FFmpeg zoompan's on."
  [segment]
  (let [{:keys [exposure samples]} (settings segment)
        time (str "(floor(on/" samples ")/30)")
        lower (reduce (fn [expr boundary]
                        (str "if(gte(" time "+0.00000001," (decimal boundary) ")," (decimal boundary) "," expr ")"))
                      "0" (boundaries segment))]
    (str "(max(" lower "," time "-" (decimal exposure) "*(1-(mod(on," samples ")+0.5)/" samples "))+"
         (decimal (:start segment)) ")")))

(defn repeat-filter [segment width height]
  (let [n (:samples (settings segment))]
    ;; Duplicate each *current* source frame N times. Do not interpolate source
    ;; video or average adjacent source frames (which would ghost UI changes).
    (str "format=yuv444p,zoompan=z=1:x=0:y=0:d=" n ":s=" width "x" height ":fps=" (* 30 n))))

(defn resolve-filter [segment]
  (let [n (:samples (settings segment))
        linear "if(lte(val/maxval,0.04045),val/12.92,maxval*pow((val/maxval+0.055)/1.055,2.4))"
        display "if(lte(val/maxval,0.0031308),val*12.92,maxval*(1.055*pow(val/maxval,1/2.4)-0.055))"
        lut (fn [expression] (str "lutrgb=" (str/join ":" (for [channel ["r" "g" "b"]] (str channel "='" expression "'")))))]
    ;; 16-bit linear-light accumulation avoids 8-bit dark-tone quantization.
    ;; Only keep the last sample of each group: tmix then contains exactly that
    ;; output frame's exposure samples, never the preceding scene/frame.
    (str "format=gbrp16le," (lut linear)
         ",tmix=frames=" n ":weights='" (str/join " " (repeat n "1")) "'"
         ",select='eq(mod(n," n ")," (dec n) ")',setpts=N/(30*TB),"
         (lut display) ",fps=30")))
