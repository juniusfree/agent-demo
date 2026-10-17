(ns agent-demo.motion-blur-test
  (:require [clojure.test :refer [deftest is]]
            [agent-demo.motion-blur :as blur] [agent-demo.cursor :as cursor]
            [agent-demo.camera :as camera]))

(def segment {:start 0 :duration 2 :source-start 0 :source-duration 1
              :footage-duration 1 :cuts [] :camera []
              :motion-blur {:exposure (/ 1.0 30) :samples 8}})

(deftest settings-validation
  (is (= {} (blur/validate {})))
  (is (nil? (blur/settings {})))
  (is (nil? (blur/settings {:motion-blur {:exposure 0}})))
  (is (= blur/defaults (blur/settings {:motion-blur {}})))
  (doseq [value [nil true {:samples 1} {:samples 17} {:samples 2.5}
                 {:exposure -1} {:exposure 0.1} {:exposure ##NaN}
                 {:exposure ##Inf} {:unknown true}]]
    (is (thrown? Exception (blur/validate {:motion-blur value})))))

(deftest exposure-clock
  (is (every? zero? (map #(blur/sample-time segment 0 %) (range 8))))
  (let [times (map #(blur/sample-time segment 15 %) (range 8))]
    (is (apply < times))
    (is (< (- 0.5 (/ 1.0 30)) (first times) (last times) 0.5)))
  (let [cut (assoc segment :cuts [{:start 0.5 :end 0.75}])]
    (is (= [0.0 0.5] (vec (blur/boundaries cut))))
    (is (every? #(= 0.5 %) (map #(blur/sample-time cut 15 %) (range 8))))))

(deftest presentation-boundaries
  (let [s (assoc segment :start 4 :camera [{:start 4.5 :duration 0}]
                 :cursor {:track [{:start -1} {:start 4.75}]})]
    (is (= [0.0 0.5 0.75] (vec (blur/boundaries s))))
    (is (every? #(= 0.5 %) (map #(blur/sample-time s 15 %) (range 8))))))

(deftest continuous-motion-and-holds
  (is (= 0.1 (cursor/source-time segment 0.123)))
  (is (= 0.123 (cursor/source-time segment 0.123 true)))
  (is (= (cursor/source-time segment 2) (cursor/source-time segment 2 true)))
  (is (= 0.76 (cursor/source-time (assoc segment :cuts [{:start 0.5 :end 0.75}]) 0.51 true)))
  (is (= "null" (camera/filter-expression segment 640 400 {:fps 240 :time-expression "on/240"}))))
