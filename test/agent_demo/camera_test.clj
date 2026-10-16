(ns agent-demo.camera-test
  (:require [clojure.test :refer :all] [agent-demo.camera :as camera]
            [agent-demo.core :as core]))

(def actions [{:op :checkpoint}])
(def project {:version 1 :scenes (mapv #(hash-map :id % :narration "Done." :actions actions) ["a" "b"])})
(def capture {:version 1 :source "source.webm" :width 1280 :height 800
              :scenes [{:id "a" :start 0 :end 3 :actions actions :checkpoint? true}
                       {:id "b" :start 3 :end 6 :actions actions :checkpoint? true}]})
(def speech {"a" [{:duration 0.5}] "b" [{:duration 0.5}]})
(def zoom-in {:at {:scene "a" :time 1} :zoom 2 :focus {:x 0.3 :y 0.4} :transition 0.5})
(def zoom-out {:at {:scene "b" :time 1} :zoom 1 :transition 0.5})
(defn near? [a b] (< (Math/abs (double (- a b))) 1.0e-8))

(deftest persistent-camera
  (let [segments (core/plan (assoc project :camera [zoom-in zoom-out]) capture speech)
        track (:camera (first segments))]
    (is (= track (:camera (second segments))))
    (is (= camera/full-view (camera/state-at track 0)))
    (is (near? 1.5 (:zoom (camera/state-at track 1.25))))
    (doseq [t [1.5 2 2.999 3 3.5 4]]
      (is (near? 2 (:zoom (camera/state-at track t)))))
    (is (= (camera/state-at track 2) (camera/state-at track 3.5))))
  (let [track (:camera (first (core/plan (assoc project :camera [zoom-in]) capture speech)))]
    (is (near? 2 (:zoom (camera/state-at track 1000)))))
  (let [track (:camera (first (core/plan (assoc project :camera [zoom-in zoom-out]) capture speech)))]
    (is (= camera/full-view (camera/state-at track 4.5)))
    (is (= camera/full-view (camera/state-at track 1000)))))

(deftest transitions-and-interruptions
  (let [instructions [(assoc zoom-in :transition 2)
                      (assoc zoom-out :at {:scene "a" :time 2} :transition 0.5)]
        track (:camera (first (core/plan (assoc project :camera instructions) capture speech)))]
    (is (near? 1.5 (get-in track [1 :from :zoom])))
    (is (near? 1.5 (:zoom (camera/state-at track 2))))
    (is (near? 1.25 (:zoom (camera/state-at track 2.25)))))
  (let [track (:camera (first (core/plan (assoc project :camera [(assoc zoom-in :transition 0)]) capture speech)))]
    (is (= camera/full-view (camera/state-at track 0.999)))
    (is (near? 2 (:zoom (camera/state-at track 1))))))

(deftest remap-cuts-and-narration-holds
  (let [p (-> project
              (assoc-in [:scenes 0 :cuts] [{:start 0.5 :end 1.5}])
              (assoc :camera [(assoc-in zoom-in [:at :time] 2) zoom-out]))
        segments (core/plan p capture speech)
        track (:camera (first segments))]
    (is (= [1.0 3.0] (mapv :start track)))
    (let [longer (core/plan p capture (assoc speech "a" [{:duration 5}]))]
      (is (= [1.0 6.25] (mapv :start (:camera (first longer)))))))
  (doseq [time [0.5 1.0 1.49]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inside a cut"
          (core/plan (-> project (assoc-in [:scenes 0 :cuts] [{:start 0.5 :end 1.5}])
                         (assoc :camera [(assoc-in zoom-in [:at :time] time)])) capture speech))))
  (is (near? 0.5 (get-in (core/plan (-> project (assoc-in [:scenes 0 :cuts] [{:start 0.5 :end 1.5}])
                                           (assoc :camera [(assoc-in zoom-in [:at :time] 1.5)]))
                                  capture speech) [0 :camera 0 :start]))))

(deftest camera-validation-and-default
  (let [segment (first (core/plan project capture speech))]
    (is (= [] (:camera segment)))
    (is (= "null" (camera/filter-expression segment 1280 800))))
  (doseq [bad [(assoc project :zoom? false)
               (assoc-in project [:scenes 0 :focus] {:zoom 2 :x 0.5 :y 0.5})
               (assoc project :camera nil)
               (assoc project :camera [zoom-out zoom-in])
               (assoc project :camera [zoom-in zoom-in])]]
    (is (thrown? clojure.lang.ExceptionInfo (core/validate-project bad))))
  (doseq [instruction [(assoc zoom-in :zoom ##NaN) (assoc zoom-in :zoom 3)
                       (assoc zoom-in :transition -1) (assoc zoom-in :transition ##Inf)
                       (dissoc zoom-in :focus) (assoc-in zoom-in [:focus :x] 2)
                       (assoc-in zoom-in [:at :scene] "missing")
                       (assoc-in zoom-in [:at :time] -1) (assoc-in zoom-in [:at :time] 9)]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/plan (assoc project :camera [instruction]) capture speech)))))
