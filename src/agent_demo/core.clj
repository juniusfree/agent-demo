(ns agent-demo.core
  (:require [clojure.string :as str] [agent-demo.camera :as camera] [agent-demo.cursor :as cursor] [agent-demo.privacy :as privacy])
  (:import [java.net URI] [java.text BreakIterator] [java.util Locale]))

(defn require! [condition message data]
  (when-not condition (throw (privacy/error message data))))

(defn finite-number? [x]
  (and (number? x) (Double/isFinite (double x))))

(def default-viewport {:width 1920 :height 1200})

(defn valid-viewport? [{:keys [width height]}]
  (and (integer? width) (integer? height)
       (even? width) (even? height)
       (<= 640 width 2560) (<= 400 height 1600)))

(defn browser-url? [value]
  (try
    (and (string? value)
         (let [uri (URI/create value)]
           (and (#{"http" "https"} (.getScheme uri))
                (some? (.getHost uri)) (nil? (.getUserInfo uri)))))
    (catch IllegalArgumentException _ false)))

(defn browser-timeout-ms [project]
  (let [timeout (get-in project [:browser :timeout-ms] 15000)]
    (require! (and (integer? timeout) (<= 1 timeout 600000))
              "Browser :timeout-ms must be an integer from 1 to 600000 (10 minutes)" {})
    timeout))

(def operations #{:goto :click :fill :hover :wait-visible :wait-text :checkpoint})
(declare scene-cuts)

(defn validate-v1 [p]
  (require! (= 1 (:version p)) "Project :version must be 1" {})
  (browser-timeout-ms p)
  (require! (valid-viewport? (get p :viewport default-viewport))
            "Viewport needs even integer dimensions: width 640–2560, height 400–1600" {})
  (require! (and (vector? (:scenes p)) (seq (:scenes p))) "Provide a nonempty :scenes vector" {})
  (require! (= (count (:scenes p)) (count (set (map :id (:scenes p)))))
            "Scene IDs must be unique" {})
  (doseq [s (:scenes p)]
    (require! (not (contains? s :camera))
              "Scene-local camera requires a version-2 edit; version 1 uses top-level :camera" {})
    (require! (and (string? (:id s)) (re-matches #"[a-zA-Z0-9_-]+" (:id s)))
              "Scene :id must be a safe filename" {:scene (:id s)})
    (require! (and (string? (:narration s)) (not (str/blank? (:narration s))))
              "Each scene needs :narration" {:scene (:id s)})
    (scene-cuts s Double/POSITIVE_INFINITY)
    (require! (vector? (:actions s)) "Scene :actions must be a vector" {:scene (:id s)})
    (doseq [a (:actions s)]
      (require! (operations (:op a)) "Unsupported browser operation" {})
      (case (:op a)
        :goto (require! (browser-url? (:url a))
                         "Goto requires an HTTP(S) URL with a host and no embedded credentials" {})
        :checkpoint nil
        (require! (and (string? (:selector a)) (not (str/blank? (:selector a))))
                  "Action requires :selector" {}))
      (when (#{:fill :wait-text} (:op a))
        (require! (string? (:text a)) "Action requires :text" {}))))
  (cursor/validate (camera/validate p)))

(defn edit-view
  "Flatten scene-local camera instructions for the existing camera compiler."
  [project]
  (if (= 2 (:version project))
    (assoc project :camera
           (vec (mapcat (fn [scene]
                          (map #(-> % (dissoc :time)
                                    (assoc :at {:scene (:id scene) :time (:time %)}))
                               (:camera scene)))
                        (:scenes project))))
    project))

(defn validate-edit [project]
  (if (= 1 (:version project))
    (validate-v1 project)
    (do
      (require! (= 2 (:version project)) "Project :version must be 1 or 2" {})
      (require! (not-any? #(contains? project %) [:camera :zoom?])
                "Version 2 camera instructions belong inside edit scenes" {})
      (doseq [scene (:scenes project)]
        (require! (and (string? (:source scene))
                       (re-matches #"[a-zA-Z0-9_-]+" (:source scene)))
                  "Each edit scene needs a :source recorded-scene ID" {})
        (require! (not-any? #(contains? scene %) [:actions :focus-selector])
                  "Browser actions and focus selectors belong in :capture :scenes, not edit scenes" {})
        (require! (vector? (get scene :camera [])) "Scene :camera must be a vector" {})
        (doseq [instruction (:camera scene)]
          (require! (not (contains? instruction :at))
                    "Scene camera instructions use :time, not :at" {})))
      (validate-v1 (-> (edit-view project)
                       (assoc :version 1)
                       (update :scenes #(mapv (fn [scene] (assoc (dissoc scene :camera) :actions [])) %))))
      project)))

(defn capture-project
  "Extract only the shooting recipe. Rendering never calls this function."
  [project]
  (if (= 1 (:version project))
    (validate-v1 project)
    (do
      (require! (= 2 (:version project)) "Project :version must be 1 or 2" {})
      (let [scenes (get-in project [:capture :scenes])]
        (require! (and (vector? scenes) (seq scenes)) "Capture requires a nonempty :capture :scenes vector" {})
        (doseq [scene scenes]
          (require! (not-any? #(contains? scene %) [:narration :cuts :camera :cursor :focus])
                    "Capture scenes contain actions, not narration or editing instructions" {}))
        (validate-v1 (-> (select-keys project [:viewport :browser])
                         (assoc :version 1
                                :scenes (mapv #(assoc % :narration "Source footage.") scenes))))))))

(defn validate-project [project]
  (if (= 1 (:version project))
    (validate-v1 project)
    (do
      (require! (= 2 (:version project)) "Project :version must be 1 or 2" {})
      (require! (or (contains? project :capture) (contains? project :scenes))
                "Version 2 needs a capture recipe, an edit, or both" {})
      (require! (not-any? #(contains? project %) [:camera :zoom?])
                "Version 2 camera instructions belong inside edit scenes" {})
      (when (contains? project :capture) (capture-project project))
      (when (contains? project :scenes) (validate-edit project))
      project)))

(defn recorded-scenes [project capture]
  (if (= 2 (:version project))
    (let [by-id (into {} (map (juxt :id identity) (:scenes capture)))]
      (mapv #(get by-id (:source %)) (:scenes project)))
    (:scenes capture)))

(defn cues [text]
  ;; A synthesis boundary is audible. Never split a sentence merely to fit a
  ;; caption: keep its prosody intact and wrap the caption visually instead.
  ;; Sentence-level measured WAVs retain exact caption timing without guessing
  ;; word timestamps that the local speech endpoint does not provide.
  (let [text (str/trim text)
        iterator (BreakIterator/getSentenceInstance Locale/US)]
    (.setText iterator text)
    (loop [start (.first iterator) end (.next iterator) out []]
      (if (= BreakIterator/DONE end)
        out
        (let [sentence (str/trim (subs text start end))]
          (recur end (.next iterator)
                 (cond-> out (not (str/blank? sentence)) (conj sentence))))))))

(defn scene-cuts [scene duration]
  (let [cuts (get scene :cuts [])
        frame-time #(/ (Math/ceil (- (* 30 (double %)) 1.0e-8)) 30.0)]
    (require! (vector? cuts) "Scene :cuts must be a vector" {:scene (:id scene)})
    (doseq [cut cuts]
      (require! (and (map? cut) (every? finite-number? ((juxt :start :end) cut))
                     (<= 0 (:start cut)) (< (:start cut) (:end cut)))
                "Cuts need nonnegative :start < :end in scene-relative seconds" {:cut cut}))
    (let [cuts (mapv #(update (update % :start frame-time) :end frame-time) cuts)]
      (doseq [cut cuts]
        (require! (and (< (:start cut) (:end cut)) (< (:end cut) duration))
                  "Cut must remove at least one frame and preserve the final scene frame" {:cut cut}))
      (doseq [[a b] (partition 2 1 cuts)]
        (require! (<= (:end a) (:start b)) "Cuts must be ordered and nonoverlapping" {}))
      cuts)))

(defn cut-filter [cuts]
  (if (seq cuts)
    (str "select='not("
         (str/join "+" (map #(format "gte(t,%.9f)*lt(t,%.9f)" (double (:start %)) (double (:end %))) cuts))
         ")',setpts=N/(30*TB)")
    "null"))

(defn validate-capture [project capture]
  (require! (= 1 (:version capture)) "Capture :version must be 1" {})
  (require! (and (string? (:source capture)) (not (str/blank? (:source capture))))
            "Capture needs a :source path" {})
  (require! (valid-viewport? capture) "Capture has unsupported dimensions" {})
  ;; Old projects without an explicit viewport retain their source dimensions.
  (when-let [viewport (:viewport project)]
    (require! (= viewport (select-keys capture [:width :height]))
              "Viewport changed; recapture to obtain real source pixels" {}))
  (require! (and (vector? (:scenes capture)) (seq (:scenes capture))
                 (= (count (:scenes capture)) (count (set (map :id (:scenes capture))))))
            "Capture must contain unique recorded scene IDs" {})
  (doseq [recorded (:scenes capture)]
    (require! (and (string? (:id recorded)) (re-matches #"[a-zA-Z0-9_-]+" (:id recorded))
                   (every? finite-number? ((juxt :start :end) recorded))
                   (<= 0 (:start recorded)) (< (:start recorded) (:end recorded)))
              "Captured scene has invalid ID or bounds" {}))
  (when (= 1 (:version project))
    (require! (= (mapv :id (:scenes project)) (mapv :id (:scenes capture)))
              "Project scenes do not match capture; recapture after adding/reordering scenes" {}))
  (doseq [[scene recorded] (map vector (:scenes project) (recorded-scenes project capture))]
    (require! (some? recorded) "Edit scene references an unknown recorded :source ID" {})
    (when (= 1 (:version project))
      (require! (= (:actions scene) (:actions recorded))
                "Browser actions changed; recapture or restore the original actions" {:scene (:id scene)}))
    (let [duration (- (:end recorded) (:start recorded))
          cuts (scene-cuts scene duration)]
      ;; Reject invalid anchors before rendering invokes speech synthesis.
      (doseq [instruction (:camera (edit-view project)) :when (= (:id scene) (get-in instruction [:at :scene]))]
        (camera/source-time->edited {:id (:id scene) :start 0 :source-duration duration :cuts cuts}
                                   (get-in instruction [:at :time])))
      (doseq [instruction (:cursor scene)]
        (camera/source-time->edited {:id (:id scene) :start 0 :source-duration duration :cuts cuts}
                                   (:time instruction)))))
  (cursor/validate-events capture))

(defn plan [project capture speech]
  (validate-edit project)
  (validate-capture project capture)
  (cursor/attach-track project capture
   (camera/attach-track (edit-view project)
   (:segments
   (reduce
    (fn [{:keys [time segments]} [scene recorded]]
      (let [source-duration (- (:end recorded) (:start recorded))
            cuts (scene-cuts scene source-duration)
            duration (- source-duration (reduce + 0 (map #(- (:end %) (:start %)) cuts)))
            audio (get speech (:id scene))
            _ (require! (and (seq audio)
                             (every? #(and (finite-number? (:duration %)) (pos? (:duration %))) audio))
                        "Missing or invalid measured speech" {:scene (:id scene)})
            spoken (reduce + 0 (map :duration audio))
            hold (max 0 (- (+ spoken 0.25) duration))
            total (+ duration hold)]
        (require! (pos? duration) "Captured scene has invalid duration" {:scene (:id scene)})
        (require! (or (zero? hold) (:checkpoint? recorded))
                  "Narration exceeds footage; mark a stable :checkpoint and recapture or shorten narration"
                  {:scene (:id scene) :extra-seconds hold})
        {:time (+ time total)
         :segments (conj segments
                         {:id (:id scene) :source-id (:id recorded) :source-start (:start recorded)
                          :source-duration source-duration :cuts cuts :footage-duration duration
                          :hold hold :duration total :start time
                          :cues (loop [remaining audio t 0 out []]
                                  (if-let [c (first remaining)]
                                    (recur (rest remaining) (+ t (:duration c))
                                           (conj out (assoc c :start t :end (+ t (:duration c)))))
                                    out))})}))
    {:time 0 :segments []}
    (map vector (:scenes project) (recorded-scenes project capture)))))))
