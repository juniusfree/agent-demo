(ns agent-demo.camera
  "Pure, persistent camera state. Source-scene anchors map to edited time."
  (:require [agent-demo.privacy :as privacy]))

(def full-view {:zoom 1.0 :x 0.5 :y 0.5})
(defn- ensure! [ok message data]
  (when-not ok (throw (privacy/error message data))))
(defn- finite? [x] (and (number? x) (Double/isFinite (double x))))

(defn validate [project]
  (ensure! (not (contains? project :zoom?))
           "Remove :zoom?; use a project :camera track to opt in" {})
  (doseq [scene (:scenes project)]
    (ensure! (not (contains? scene :focus))
             "Move scene :focus into an explicit project :camera instruction" {:scene (:id scene)}))
  (let [track (get project :camera [])
        indices (zipmap (map :id (:scenes project)) (range))]
    (ensure! (vector? track) ":camera must be a vector" {})
    (doseq [{:keys [at zoom focus] :as instruction} track]
      (ensure! (and (contains? indices (:scene at))
                    (finite? (:time at)) (<= 0 (:time at)))
               "Camera :at needs an existing :scene and nonnegative source-relative :time" {:instruction instruction})
      (ensure! (and (finite? zoom) (<= 1 zoom 2.5))
               "Camera :zoom must be between 1 and 2.5" {:instruction instruction})
      (ensure! (or (= 1.0 (double zoom))
                    (and (every? finite? ((juxt :x :y) focus))
                         (<= 0 (:x focus) 1) (<= 0 (:y focus) 1)))
               "Zoomed camera instructions need normalized :focus {:x ... :y ...}" {:instruction instruction})
      (let [transition (get instruction :transition 0.5)]
        (ensure! (and (finite? transition) (<= 0 transition))
                 "Camera :transition must be a nonnegative number of seconds" {:instruction instruction})))
    (doseq [[a b] (partition 2 1 track)]
      (let [key-of #(vector (indices (get-in % [:at :scene])) (get-in % [:at :time]))]
        (ensure! (neg? (compare (key-of a) (key-of b)))
                 "Camera instructions must be strictly ordered by scene and source time" {}))))
  project)

(defn source-time->edited [segment time]
  (ensure! (<= time (:source-duration segment))
           "Camera anchor exceeds captured scene duration" {:scene (:id segment) :time time})
  (ensure! (not-any? #(and (<= (:start %) time) (< time (:end %))) (:cuts segment))
           "Camera anchor falls inside a cut; reposition it explicitly" {:scene (:id segment) :time time})
  (+ (:start segment) time
     (- (reduce + 0 (for [{:keys [start end]} (:cuts segment) :when (<= end time)] (- end start))))))

(defn state-at [track time]
  (if-let [{:keys [start duration from to]} (last (take-while #(<= (:start %) time) track))]
    (let [u (if (zero? duration) 1.0 (min 1.0 (max 0.0 (/ (- time start) duration))))
          ease (* u u (- 3 (* 2 u)))]
      (into {} (for [k [:zoom :x :y]] [k (+ (k from) (* ease (- (k to) (k from))))])))
    full-view))

(defn compile-track [instructions segments]
  (let [by-id (into {} (map (juxt :id identity) segments))]
    (reduce (fn [track {:keys [at zoom focus] :as instruction}]
              (let [time (source-time->edited (by-id (:scene at)) (:time at))
                    target (if (= 1.0 (double zoom)) full-view (assoc focus :zoom zoom))]
                (ensure! (or (empty? track) (< (:start (peek track)) time))
                         "Camera anchors coincide after editing; reposition them" {})
                (conj track {:start time :duration (get instruction :transition 0.5)
                             :from (state-at track time) :to target :at at})))
            [] instructions)))

(defn attach-track [project segments]
  (let [track (compile-track (:camera project) segments)]
    (mapv #(assoc % :camera track) segments)))

(defn- decimal [x] (String/format java.util.Locale/ROOT "%.9f" (to-array [(double x)])))
(defn- expression [track time key]
  (reduce
   (fn [previous {:keys [start duration from to]}]
     (let [u (str "min(1,max(0,(" time "-" (decimal start) ")/" (decimal (max duration 1.0e-9)) "))")
           ease (str "(" u "*" u "*(3-2*" u "))")
           value (if (zero? duration) (decimal (key to))
                     (str "(" (decimal (key from)) "+" (decimal (- (key to) (key from))) "*" ease ")"))]
       (str "if(lt(" time "," (decimal start) ")," previous "," value ")")))
   (decimal (key full-view)) track))

(defn filter-expression [{:keys [camera start duration]} width height]
  (let [start (or start 0)
        track (take-while #(< (:start %) (+ start duration)) camera)]
    (if (or (empty? track) (every? #(and (= 1.0 (double (get-in % [:from :zoom])))
                                         (= 1.0 (double (get-in % [:to :zoom])))) track))
      "null"
      (let [time (str "(on/30+" (decimal start) ")")
            z (expression track time :zoom)
            x (expression track time :x) y (expression track time :y)]
        ;; Full chroma and 4x working pixels prevent integer crop jitter.
        (str "format=yuv444p,scale=" (* 4 width) ":" (* 4 height) ":flags=bicubic,"
             "zoompan=z='" z "':x='max(0,min(iw-iw/zoom,iw*(" x ")-iw/zoom/2))'"
             ":y='max(0,min(ih-ih/zoom,ih*(" y ")-ih/zoom/2))':d=1:s=" width "x" height ":fps=30")))))
