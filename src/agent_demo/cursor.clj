(ns agent-demo.cursor
  "Pure cursor settings, source-clock sampling, and edit-time state."
  (:require [agent-demo.camera :as camera] [agent-demo.privacy :as privacy]))

(def defaults {:size 30 :style :arrow :smoothing :smooth :visible? true :clicks? true})
(defn- ensure! [ok message]
  (when-not ok (throw (privacy/error message {}))))
(defn- finite? [x] (and (number? x) (Double/isFinite (double x))))

(defn validate-settings [settings]
  (ensure! (map? settings) "Cursor settings must be a map")
  (ensure! (every? (set (keys defaults)) (keys settings)) "Unknown cursor setting")
  (let [{:keys [size style smoothing visible? clicks?]} (merge defaults settings)]
    (ensure! (and (finite? size) (<= 8 size 96)) "Cursor :size must be between 8 and 96 source pixels")
    (ensure! (#{:arrow :dot} style) "Cursor :style must be :arrow or :dot")
    (ensure! (#{:linear :smooth} smoothing) "Cursor :smoothing must be :linear or :smooth")
    (ensure! (and (boolean? visible?) (boolean? clicks?)) "Cursor :visible? and :clicks? must be booleans"))
  settings)

(defn validate [project]
  (validate-settings (get project :cursor {}))
  (doseq [scene (:scenes project)]
    (let [track (get scene :cursor [])]
      (ensure! (vector? track) "Scene :cursor must be a vector")
      (doseq [instruction track]
        (ensure! (and (map? instruction) (finite? (:time instruction)) (<= 0 (:time instruction)))
                 "Cursor instructions need nonnegative source-scene :time")
        (validate-settings (dissoc instruction :time)))
      (ensure! (every? (fn [[a b]] (< (:time a) (:time b))) (partition 2 1 track))
               "Cursor instructions must be strictly ordered")))
  project)

(defn validate-events [capture]
  (when (contains? capture :cursor)
    (let [cursor (:cursor capture)]
      (ensure! (and (map? cursor) (= :source-video (:clock cursor)) (vector? (:events cursor)))
               "Capture cursor needs :clock :source-video and an :events vector")
      (doseq [{:keys [at type x y]} (:events cursor)]
        (ensure! (and (#{:pointer :click} type) (every? finite? [at x y]) (<= 0 at)
                      (<= 0 x (:width capture)) (<= 0 y (:height capture))
                      (<= at (apply max (map :end (:scenes capture)))))
                 "Captured cursor event has invalid time, type, or coordinates"))
      (ensure! (every? (fn [[a b]] (<= (:at a) (:at b))) (partition 2 1 (:events cursor)))
               "Captured cursor events must be ordered")))
  capture)

(defn align-time
  "Map monotonic seconds to video seconds using observed scene-marker anchors."
  [anchors time]
  (let [[a b] (or (first (filter (fn [[_ b]] (<= time (:wall b))) (partition 2 1 anchors)))
                  (take-last 2 anchors))
        u (max 0.0 (min 1.0 (/ (- time (:wall a)) (- (:wall b) (:wall a)))))]
    (+ (:video a) (* u (- (:video b) (:video a))))))

(defn settings-at [track time]
  (:settings (last (take-while #(<= (:start %) time) track))))

(defn attach-track [project capture segments]
  (let [by-id (into {} (map (juxt :id identity) segments))
        track (reduce
               (fn [track [id instruction]]
                 (let [time (camera/source-time->edited (by-id id) (:time instruction))]
                   (ensure! (or (= 1 (count track)) (< (:start (peek track)) time))
                            "Cursor anchors coincide after editing; reposition them")
                   (conj track {:start time :settings (merge (:settings (peek track)) (dissoc instruction :time))})))
               [{:start -1.0 :settings (merge defaults (:cursor project))}]
               (for [scene (:scenes project) instruction (:cursor scene)] [(:id scene) instruction]))]
    (mapv #(assoc % :cursor {:track track :events (get-in capture [:cursor :events] [])}) segments)))

(defn source-time
  "Inverse cuts on the 30fps grid; checkpoint holds sample the final source frame."
  ([segment time] (source-time segment time false))
  ([segment time continuous?]
  (let [frame (if continuous? (min time (:footage-duration segment))
                 (/ (Math/floor (+ (* 30 (min time (:footage-duration segment))) 1.0e-7)) 30.0))
        local (reduce (fn [t {:keys [start end]}] (if (>= (+ t 1.0e-8) start) (+ t (- end start)) t))
                      frame (:cuts segment))
        last-frame (/ (max 0 (dec (long (Math/ceil (- (* 30 (:source-duration segment)) 1.0e-7))))) 30.0)]
    (+ (:source-start segment) (min local last-frame)))))

(defn position-at [events time smoothing]
  (let [points (filter #(= :pointer (:type %)) events)
        before (last (take-while #(<= (:at %) time) points))
        after (first (drop-while #(<= (:at %) time) points))]
    (when before
      (if after
        (let [u (/ (- time (:at before)) (- (:at after) (:at before)))
              ease (if (= :smooth smoothing) (* u u (- 3 (* 2 u))) u)]
          (into {} (for [k [:x :y]] [k (+ (k before) (* ease (- (k after) (k before))))])))
        (select-keys before [:x :y])))))

(defn frame-state
  "Sample source motion, but age retained click pulses in edited time (also during holds)."
  ([segment time] (frame-state segment time false))
  ([segment time continuous?]
  (let [{:keys [events track]} (:cursor segment)
        settings (settings-at track (+ (:start segment) time))
        source (source-time segment time continuous?)
        clicks (for [event events
                     :when (and (= :click (:type event))
                                (<= (:source-start segment) (:at event))
                                (< (:at event) (+ (:source-start segment) (:source-duration segment))))
                     :let [local (- (:at event) (:source-start segment))]
                     :when (not-any? #(and (<= (:start %) local) (< local (:end %))) (:cuts segment))
                     :let [edited (- (camera/source-time->edited segment local) (:start segment))
                           age (- time edited)]
                     :when (<= 0 age 0.5)]
                 (assoc (select-keys event [:x :y]) :age age))]
    {:settings settings :position (position-at events source (:smoothing settings))
     :clicks (if (and (:visible? settings) (:clicks? settings)) (vec clicks) [])})))
