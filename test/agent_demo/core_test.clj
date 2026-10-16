(ns agent-demo.core-test
  (:require [clojure.test :refer :all] [agent-demo.core :as c] [agent-demo.capture :as capture]
            [agent-demo.speech :as speech] [agent-demo.io :as dio]))

(def project {:version 1 :scenes [{:id "a" :narration "A useful demo." :actions [{:op :checkpoint}]}]})
(def recorded {:version 1 :source "source.webm" :width 1280 :height 800
               :scenes [{:id "a" :start 1 :end 3 :checkpoint? true :actions [{:op :checkpoint}]}]})
(def measured {"a" [{:text "A useful demo." :path "audio.wav" :duration 4.0}]})

(deftest project-validation
  (is (= project (c/validate-project project)))
  (doseq [invalid [(assoc project :version 2)
                   (assoc project :scenes [])
                   (update project :scenes into (:scenes project))
                   (assoc-in project [:scenes 0 :id] "../escape")
                   (assoc-in project [:scenes 0 :narration] " ")
                   (assoc-in project [:scenes 0 :actions] [{:op :shell}])
                   (assoc-in project [:scenes 0 :actions] [{:op :goto :url "file:///private"}])
                   (assoc-in project [:scenes 0 :actions] [{:op :fill :selector "#a"}])
                   (assoc-in project [:scenes 0 :focus] {:x ##NaN :y 0.5 :zoom 1.5})]]
    (is (thrown? clojure.lang.ExceptionInfo (c/validate-project invalid)))))

(deftest configurable-viewport
  (is (= {:width 1920 :height 1200} c/default-viewport))
  (doseq [viewport [{:width 1920 :height 1200} {:width 1280 :height 800}
                    {:width 1920 :height 1080}]]
    (is (c/validate-project (assoc project :viewport viewport))))
  (doseq [viewport [{:width 1921 :height 1200} {:width 1920 :height 0}
                    {:width 99999 :height 800} {:width 1920.0 :height 1200} nil]]
    (is (thrown? clojure.lang.ExceptionInfo (c/validate-project (assoc project :viewport viewport)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recapture"
        (c/validate-capture (assoc project :viewport c/default-viewport) recorded)))
  (is (= recorded (c/validate-capture project recorded))))

(deftest pure-timeline
  (let [p (c/plan project recorded measured) s (first p)]
    (is (= p (c/plan project recorded measured)))
    (is (= 2 (:source-duration s)))
    (is (= 2.25 (:hold s)))
    (is (= 4.25 (:duration s)))
    (is (= 0 (get-in s [:cues 0 :start])))
    (is (= 4.0 (get-in s [:cues 0 :end])))
    (is (= 1 (:source-start s)))))
(deftest revisions-and-invalid-capture
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checkpoint"
                       (c/plan project (assoc-in recorded [:scenes 0 :checkpoint?] false) measured)))
  (is (zero? (:hold (first (c/plan project (assoc-in recorded [:scenes 0 :checkpoint?] false)
                                 {"a" [{:duration 1.0}]})))))
  (doseq [bad [(assoc recorded :version 2)
               (assoc recorded :width 0)
               (assoc-in recorded [:scenes 0 :id] "changed")
               (assoc-in recorded [:scenes 0 :end] 0)
               (assoc-in recorded [:scenes 0 :end] ##Inf)
               (assoc-in recorded [:scenes 0 :actions] [])]]
    (is (thrown? clojure.lang.ExceptionInfo (c/plan project bad measured))))
  (is (thrown? clojure.lang.ExceptionInfo (c/plan project recorded {}))))
(deftest agent-authored-cuts
  (let [p (assoc-in project [:scenes 0 :cuts] [{:start 0.5 :end 1.5}])
        segment (first (c/plan p recorded measured))]
    (is (= 2 (:source-duration segment)))
    (is (= 1.0 (:footage-duration segment)))
    (is (= 3.25 (:hold segment)))
    (is (= 4.25 (:duration segment)))
    (is (= 4.0 (get-in segment [:cues 0 :end])))
    (is (= 1.25 (:duration (first (c/plan p recorded {"a" [{:duration 1.0}]}))))))
  (is (= [{:start (/ 1.0 30) :end (/ 2.0 30)}]
         (c/scene-cuts {:cuts [{:start 0.01 :end 0.04}]} 2)))
  (is (= "null" (c/cut-filter [])))
  (doseq [cuts [nil [{:start -1 :end 1}] [{:start 1 :end 1}]
                [{:start 0 :end 2}] [{:start 0 :end 99}]
                [{:start 0.01 :end 0.02}] [{:start ##NaN :end 1}]
                [{:start 0.5 :end 1.5} {:start 1 :end 1.7}]
                [{:start 1 :end 1.5} {:start 0 :end 0.5}]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (c/plan (assoc-in project [:scenes 0 :cuts] cuts) recorded measured)))))

(deftest cues-preserve-sentence-prosody
  (let [text "one two three four five six seven eight nine ten."]
    (is (= [text] (c/cues text))))
  (is (= ["Save the project." "The confirmation shows the saved name and visibility."]
         (c/cues "Save the project. The confirmation shows the saved name and visibility.")))
  (is (= ["A longer sentence without closing punctuation stays together"]
         (c/cues "A longer sentence without closing punctuation stays together")))
  (is (= ["Set the value to 1.25 before saving."]
         (c/cues "Set the value to 1.25 before saving.")))
  (is (= [] (c/cues "  "))))
(deftest marker-roundtrip
  (doseq [id (range 1 64)]
    (is (= id (capture/decode-marker [(+ 17 (* 28 (mod id 8))) (+ 15 (* 28 (quot id 8))) 239]))))
  (is (nil? (capture/decode-marker [0 0 0]))))
(deftest local-endpoint-only
  (is (= "http://localhost:8880" (speech/endpoint {:endpoint "http://localhost:8880/"})))
  (doseq [url ["https://example.org" "http://localhost:8880/path"
               "http://user@localhost:8880" "http://localhost:8880?secret=x"]]
    (is (thrown? clojure.lang.ExceptionInfo (speech/endpoint {:endpoint url})))))
(deftest reuse-does-not-own
  (let [calls (atom [])
        result (speech/with-service {} "ignored"
                 (fn [_] :ok)
                 {:ready? (constantly true)
                  :launch-fn (fn [& _] (swap! calls conj :launch))
                  :stop-fn #(swap! calls conj [:stop %])})]
    (is (= :ok result))
    (is (empty? @calls))))
(deftest startup-and-shutdown
  (let [calls (atom []) checks (atom 0)]
    (is (= :ok
           (speech/with-service {} "ignored" (fn [_] :ok)
             {:ready? (fn [_] (> (swap! checks inc) 1))
              :launch-fn (fn [& _] (swap! calls conj :launch) :owned)
              :alive? (constantly true)
              :stop-fn #(swap! calls conj [:stop %])})))
    (is (= [:launch [:stop :owned]] @calls))))
(deftest startup-failures-clean-up
  (doseq [alive [true false]]
    (let [stopped (atom nil)]
      (is (thrown? clojure.lang.ExceptionInfo
            (speech/with-service {:startup-timeout-ms 0} "ignored" (fn [_] :unexpected)
              {:ready? (constantly false) :alive? (constantly alive)
               :launch-fn (fn [& _] :owned) :stop-fn #(reset! stopped %)})))
      (is (= :owned @stopped)))))
(deftest generation-failure-cleans-up
  (let [checks (atom 0) stopped (atom nil)]
    (is (thrown? Exception
          (speech/with-service {} "ignored" (fn [_] (throw (Exception. "speech failed")))
            {:ready? (fn [_] (> (swap! checks inc) 1)) :alive? (constantly true)
             :launch-fn (fn [& _] :owned) :stop-fn #(reset! stopped %)})))
    (is (= :owned @stopped))))
(deftest output-is-never-overwritten
  (is (thrown? clojure.lang.ExceptionInfo (dio/fresh-dir! "src"))))
