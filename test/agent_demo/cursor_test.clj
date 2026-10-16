(ns agent-demo.cursor-test
  (:require [clojure.test :refer [deftest is testing]]
            [agent-demo.cursor :as cursor] [agent-demo.core :as core]))

(def events [{:type :pointer :at 0 :x 100 :y 100}
             {:type :pointer :at 1 :x 100 :y 100}
             {:type :pointer :at 2 :x 300 :y 100}
             {:type :click :at 2.1 :x 300 :y 100}
             {:type :pointer :at 3 :x 300 :y 100}
             {:type :pointer :at 4 :x 400 :y 200}])
(def segment {:id "s" :source-start 0 :source-duration 4 :footage-duration 3
              :start 0 :duration 5 :cuts [{:start 0.5 :end 1.5}]
              :cursor {:events events :track [{:start -1 :settings cursor/defaults}]}})

(deftest cursor-sampling
  (is (= {:x 100.0 :y 100.0} (cursor/position-at events 0.8 :smooth)) "No drifting during idle")
  (is (= 150.0 (:x (cursor/position-at events 1.25 :linear))))
  (is (= 131.25 (:x (cursor/position-at events 1.25 :smooth))))
  (is (nil? (cursor/position-at events -0.1 :smooth)))
  (is (= {:x 400 :y 200} (cursor/position-at events 100 :smooth)))
  (is (= 0.0 (cursor/source-time segment 0)))
  (is (= 1.5 (cursor/source-time segment 0.5)) "Cut end is retained, not interpolated across output splice")
  (is (= (/ 119.0 30) (cursor/source-time segment 4)))
  (is (= (cursor/frame-state segment 3) (cursor/frame-state segment 4)) "Stable checkpoint pointer")
  (is (= 1 (count (:clicks (cursor/frame-state segment 1.2)))))
  (is (empty? (:clicks (cursor/frame-state segment 2)))))

(deftest settings-follow-edited-time
  (let [project {:scenes [{:id "s" :cursor [{:time 2 :visible? false}]}]}
        s (first (cursor/attach-track project {:cursor {:events events}} [segment]))]
    (is (true? (get-in (cursor/frame-state s 0.9) [:settings :visible?])))
    (is (false? (get-in (cursor/frame-state s 1.1) [:settings :visible?]))))
  (let [s (assoc segment :source-duration 8 :footage-duration 6
                 :cuts [{:start 1 :end 2} {:start 3 :end 4}])]
    (is (= 2.0 (cursor/source-time s 1)))
    (is (= 4.0 (cursor/source-time s 2))))
  (let [points [{:type :pointer :at 0 :x 10 :y 10}
                {:type :pointer :at 1 :x 10 :y 10}
                {:type :pointer :at 1 :x 20 :y 20}]]
    (is (= 10.0 (:x (cursor/position-at points 0.9 :smooth))))
    (is (= 20 (:x (cursor/position-at points 1 :smooth))))))

(deftest pulses-respect-cuts-and-holds
  (let [s (assoc segment :cuts [{:start 2 :end 2.5}])]
    (is (empty? (:clicks (cursor/frame-state s 2.1))) "Removed clicks never reappear"))
  (let [s (-> segment (assoc :cuts [] :footage-duration 4)
              (assoc-in [:cursor :events] [{:type :pointer :at 0 :x 100 :y 100}
                                          {:type :click :at 3.9 :x 100 :y 100}]))]
    (is (= 1 (count (:clicks (cursor/frame-state s 4.1)))))
    (is (empty? (:clicks (cursor/frame-state s 4.6))) "A hold must not freeze a glowing pulse")
    (is (empty? (:clicks (cursor/frame-state (assoc-in s [:cursor :track 0 :settings :visible?] false) 4.1))))))

(deftest observed-clock-mapping
  (let [anchors [{:wall 0 :video 0.2} {:wall 2 :video 2.4} {:wall 5 :video 5.1}]]
    (doseq [[wall video] [[0 0.2] [1 1.3] [2 2.4] [3 3.3] [5 5.1]]]
      (is (< (abs (- video (cursor/align-time anchors wall))) 1.0e-8))))
  (is (= 7.0 (cursor/align-time [{:wall 0 :video 3} {:wall 10 :video 7}] 20))))

(def recorded {:version 1 :source "synthetic.webm" :width 640 :height 400
               :scenes [{:id "a" :start 0 :end 2 :checkpoint? true}
                        {:id "b" :start 2 :end 4 :checkpoint? true}]
               :cursor {:clock :source-video :events events}})
(def edit {:version 2 :cursor {:size 40}
           :scenes [{:id "first" :source "b" :narration "First."
                     :cursor [{:time 0 :style :dot} {:time 1 :visible? false}]}
                    {:id "second" :source "a" :narration "Second."}
                    {:id "replay" :source "b" :narration "Replay."
                     :cursor [{:time 0 :visible? true :smoothing :linear}]}]})
(def speech (into {} (for [s (:scenes edit)] [(:id s) [{:duration 3 :text (:narration s)}]])))

(deftest persistent-edit-settings
  (let [[first second replay] (core/plan edit recorded speech)]
    (is (= 3.25 (:duration first)))
    (is (= :dot (get-in (cursor/frame-state first 0) [:settings :style])))
    (is (= 40 (get-in (cursor/frame-state replay 0) [:settings :size])))
    (is (false? (get-in (cursor/frame-state second 0) [:settings :visible?])))
    (is (true? (get-in (cursor/frame-state replay 0) [:settings :visible?])))
    (is (= 300.0 (get-in (cursor/frame-state first 0) [:position :x])))
    (is (= 100.0 (get-in (cursor/frame-state second 0) [:position :x])))
    (is (= 300.0 (get-in (cursor/frame-state replay 0) [:position :x])))))

(deftest invalid-cursor-data
  (doseq [settings [nil [] {:size 0} {:size ##NaN} {:style :hand}
                    {:smoothing :unknown} {:visible? 1} {:clicks? nil} {:color "red"}]]
    (is (thrown? clojure.lang.ExceptionInfo (core/validate-edit (assoc edit :cursor settings)))))
  (doseq [instructions [nil {} [{:time -1}] [{:time ##Inf}]
                        [{:time 1} {:time 0}] [{:time 0} {:time 0}]]]
    (is (thrown? clojure.lang.ExceptionInfo (core/validate-edit (assoc-in edit [:scenes 0 :cursor] instructions)))))
  (is (thrown? clojure.lang.ExceptionInfo
        (core/validate-capture (assoc-in edit [:scenes 0 :cursor] [{:time 3}]) recorded)))
  (is (thrown? clojure.lang.ExceptionInfo
        (core/validate-capture (assoc-in edit [:scenes 0 :cuts] [{:start 0.5 :end 1.5}]) recorded)))
  (doseq [bad [(assoc recorded :cursor nil)
               (assoc recorded :cursor false)
               (assoc-in recorded [:cursor :clock] :wall)
               (assoc-in recorded [:cursor :events 0 :x] 700)
               (assoc-in recorded [:cursor :events 0 :at] -1)
               (assoc-in recorded [:cursor :events 0 :type] :unknown)
               (assoc-in recorded [:cursor :events 0 :y] ##NaN)
               (update-in recorded [:cursor :events] #(vec (reverse %)))]]
    (is (thrown? clojure.lang.ExceptionInfo (core/validate-capture edit bad)))))
