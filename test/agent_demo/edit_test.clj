(ns agent-demo.edit-test
  (:require [clojure.test :refer :all]
            [agent-demo.core :as core]
            [agent-demo.camera :as camera]))

(def shoot {:version 2 :capture {:scenes
                                 [{:id "a" :actions [{:op :goto :url "http://localhost:8799"}
                                                    {:op :checkpoint}]}
                                  {:id "b" :actions [{:op :checkpoint}]}]}})
(def recorded {:version 1 :source "source.webm" :width 1280 :height 800
               :scenes [{:id "a" :start 0 :end 3 :checkpoint? true}
                        {:id "b" :start 3 :end 6 :checkpoint? true}]})
(def edit {:version 2 :scenes [{:id "result" :source "b" :narration "Result."}
                               {:id "explain" :source "a" :narration "Explain."}
                               {:id "reprise" :source "b" :narration "Again."}]})
(def audio (zipmap ["result" "explain" "reprise"] (repeat [{:duration 0.5}])))

(deftest independent-source-and-edit
  (is (= shoot (core/validate-project shoot)))
  (is (= edit (core/validate-project edit)))
  (is (= ["a" "b"] (mapv :id (:scenes (core/capture-project shoot)))))
  (is (thrown? clojure.lang.ExceptionInfo (core/capture-project edit)))
  (is (thrown? clojure.lang.ExceptionInfo (core/validate-edit shoot)))
  (let [plan (core/plan edit recorded audio)]
    (is (= ["result" "explain" "reprise"] (mapv :id plan)))
    (is (= ["b" "a" "b"] (mapv :source-id plan)))
    (is (= [3 0 3] (mapv :source-start plan)))
    (is (= [0 3 6] (mapv :start plan)))
    ;; Rendering uses only source references. A stale/changed capture recipe
    ;; cannot rerun actions or change which frames the edit references.
    (is (= plan (core/plan (assoc edit :capture {:scenes [{:op :invalid}]})
                          recorded audio))))
  (let [omitted (assoc edit :scenes [(first (:scenes edit))])]
    (is (= ["b"] (mapv :source-id (core/plan omitted recorded audio))))))

(deftest camera-and-cuts-belong-to-each-instance
  (let [p (-> edit
              (assoc-in [:scenes 0 :cuts] [{:start 0.5 :end 1.5}])
              (assoc-in [:scenes 0 :camera]
                        [{:time 2 :zoom 2 :focus {:x 0.3 :y 0.4} :transition 0}])
              (assoc-in [:scenes 2 :camera] [{:time 1 :zoom 1 :transition 0.5}]))
        plan (core/plan p recorded audio)
        track (:camera (first plan))]
    (is (= [2.0 3 3] (mapv :footage-duration plan)))
    (is (= [1.0 6.0] (mapv :start track)))
    (is (= 2.0 (:zoom (camera/state-at track 4))))
    (is (= camera/full-view (camera/state-at track 6.5))))
  (let [p (assoc-in edit [:scenes 1 :camera] [{:time 0 :zoom 1}])
        plan (core/plan p recorded (assoc audio "result" [{:duration 5}]))]
    (is (= 2.25 (:hold (first plan))))
    (is (= 5.25 (get-in plan [0 :camera 0 :start])))))

(deftest invalid-edits-fail-before-render
  (doseq [p [(assoc edit :camera [])
             (assoc-in edit [:scenes 0 :actions] [])
             (assoc-in edit [:scenes 0 :focus-selector] "#name")
             (assoc-in edit [:scenes 0 :id] "explain")
             (assoc-in edit [:scenes 0 :source] "../secret")
             (assoc-in edit [:scenes 0 :source] "missing")
             (assoc-in edit [:scenes 0 :camera] nil)
             (assoc-in edit [:scenes 0 :camera] [{:at {:scene "result" :time 0} :zoom 1}])
             (assoc-in edit [:scenes 0 :camera] [{:time 4 :zoom 1}])
             (assoc-in edit [:scenes 0 :camera] [{:time 1 :zoom 1} {:time 0 :zoom 1}])
             (-> edit
                 (assoc-in [:scenes 0 :camera] [{:time 1 :zoom 1}])
                 (assoc-in [:scenes 0 :cuts] [{:start 0.5 :end 1.5}]))]]
    (is (thrown? clojure.lang.ExceptionInfo (core/plan p recorded audio))))
  (doseq [capture [(update recorded :scenes conj (first (:scenes recorded)))
                   (assoc-in recorded [:scenes 0 :end] ##NaN)
                   (assoc recorded :scenes [])]]
    (is (thrown? clojure.lang.ExceptionInfo (core/plan edit capture audio))))
  (is (thrown? clojure.lang.ExceptionInfo
        (core/plan edit (assoc-in recorded [:scenes 1 :checkpoint?] false)
                   (assoc audio "result" [{:duration 5}]))))
  (is (thrown? clojure.lang.ExceptionInfo
        (core/plan (assoc edit :viewport {:width 1920 :height 1200}) recorded audio))))

(deftest legacy-migration-preserves-the-edit
  (let [legacy {:version 1
                :scenes [{:id "a" :narration "A." :actions [] :cuts [{:start 0.5 :end 1.0}]}
                         {:id "b" :narration "B." :actions []}]
                :camera [{:at {:scene "a" :time 1.5} :zoom 1.5
                          :focus {:x 0.5 :y 0.5} :transition 0.5}
                         {:at {:scene "b" :time 0} :zoom 1 :transition 0.5}]}
        migrated {:version 2
                  :scenes [{:id "a" :source "a" :narration "A." :cuts [{:start 0.5 :end 1.0}]
                            :camera [{:time 1.5 :zoom 1.5 :focus {:x 0.5 :y 0.5} :transition 0.5}]}
                           {:id "b" :source "b" :narration "B."
                            :camera [{:time 0 :zoom 1 :transition 0.5}]}]}
        source (update recorded :scenes #(mapv (fn [s] (assoc s :actions [])) %))
        speech {"a" [{:duration 4}] "b" [{:duration 0.5}]}]
    (is (= (core/plan legacy source speech) (core/plan migrated source speech)))))

(deftest capture-recipe-has-no-editing
  (doseq [[key value] [[:narration "No."] [:cuts []] [:camera []]]]
    (is (thrown? clojure.lang.ExceptionInfo
          (core/capture-project (assoc-in shoot [:capture :scenes 0 key] value)))))
  (is (thrown? clojure.lang.ExceptionInfo (core/validate-project {:version 2}))))
