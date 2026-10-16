(ns agent-demo.zoom-regression
  (:require [agent-demo.core :as core] [agent-demo.camera :as camera] [agent-demo.io :as dio] [clojure.string :as str]))

(defn centroids [^bytes frames width height]
  (let [size (* width height)]
    (mapv
     (fn [frame]
       (let [offset (* frame size)]
         (loop [i 0 mass 0.0 mx 0.0 my 0.0]
           (if (= i size)
             [(/ mx mass) (/ my mass)]
             (let [weight (double (max 0 (- (bit-and 255 (aget frames (+ offset i))) 20)))]
               (recur (inc i) (+ mass weight)
                      (+ mx (* weight (rem i width)))
                      (+ my (* weight (quot i width)))))))))
     (range (quot (alength frames) size)))))

(defn motion-error [track points]
  (let [residuals (map-indexed
                   (fn [i [x y]]
                     (let [z (:zoom (camera/state-at track (/ i 30.0)))]
                       [(- x (+ 160 (* (- 84.5 160) z)))
                        (- y (+ 100 (* (- 64.5 100) z)))])) points)
        errors (mapcat (fn [[a b]] (map - b a)) (partition 2 1 residuals))]
    (Math/sqrt (/ (reduce + (map #(* % %) errors)) (count errors)))))

(defn -main [& _]
  (let [track (camera/compile-track
               [{:at {:scene "a" :time 0.2} :zoom 1.6 :focus {:x 0.5 :y 0.5} :transition 1}
                {:at {:scene "a" :time 2.8} :zoom 1 :transition 0.8}]
               [{:id "a" :start 0 :source-duration 4 :cuts []}])
        filter (camera/filter-expression {:start 0 :duration 4 :camera track} 320 200)
        legacy (str/replace filter #"^format=yuv444p,scale=[^,]+," "")
        frames (fn [f duration]
                 (centroids
                  (dio/binary! "ffmpeg" "-v" "error" "-f" "lavfi" "-i"
                               (str "color=black:s=320x200:r=30:d=" duration
                                    ",drawbox=x=80:y=60:w=10:h=10:color=white:t=fill")
                               "-vf" (str f ",format=gray") "-pix_fmt" "gray" "-f" "rawvideo" "-")
                  320 200))
        points (frames filter 4)
        split-points (mapcat #(frames (camera/filter-expression {:start % :duration 2 :camera track} 320 200) 2)
                             [0 2])
        transition-split (concat (frames (camera/filter-expression {:start 0 :duration 0.8 :camera track} 320 200) 0.8)
                                 (frames (camera/filter-expression {:start 0.8 :duration 3.2 :camera track} 320 200) 3.2))
        old (motion-error track (frames legacy 4)) improved (motion-error track points)]
    (core/require! (= 120 (count points) (count split-points)) "Camera lost frames" {})
    (core/require! (every? (fn [[a b]] (every? #(< (Math/abs (double %)) 0.01) (map - a b)))
                         (map vector points split-points)) "Camera reset across scene boundary" {})
    (core/require! (= points (vec transition-split)) "Transition reset across scene boundary" {})
    (core/require! (apply = (subvec points 42 78)) "Camera moved during hold" {})
    (prn {:native-pixel-motion-error old :supersampled-motion-error improved
          :reduction-percent (* 100 (- 1 (/ improved old)))})
    (core/require! (< improved (* old 0.5)) "Zoom jitter regression: expected at least 50% less motion error" {})))
