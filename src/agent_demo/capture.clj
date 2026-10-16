(ns agent-demo.capture
  (:require [agent-demo.core :as core] [agent-demo.io :as dio] [clojure.java.io :as io] [agent-demo.privacy :as privacy])
  (:import [com.microsoft.playwright Playwright Playwright$CreateOptions BrowserType$LaunchOptions Browser$NewContextOptions
            Locator$WaitForOptions]
           [com.microsoft.playwright.options WaitForSelectorState]
           [java.nio.file Paths]))

(defn now [] (/ (System/nanoTime) 1e9))
(defn path [s] (Paths/get (str s) (make-array String 0)))
(defn install-pointer! [page]
  (.evaluate page (slurp (io/resource "pointer.js"))))
(defn target! [page selector]
  (let [loc (.locator page selector)]
    (core/require! (= 1 (.count loc)) "Selector must identify exactly one element" {:selector selector})
    (.scrollIntoViewIfNeeded loc)
    (let [box (.boundingBox loc)]
      (core/require! (some? box) "Target is not visible" {:selector selector})
      [loc {:x (+ (.-x box) (/ (.-width box) 2)) :y (+ (.-y box) (/ (.-height box) 2))}])))
(defn move! [page position destination events base]
  (install-pointer! page)
  (let [{sx :x sy :y} @position {dx :x dy :y} destination]
    (doseq [step (range 1 19)]
      (let [t (/ step 18.0) e (* t t (- 3 (* 2 t)))
            x (+ sx (* (- dx sx) e)) y (+ sy (* (- dy sy) e))]
        (.evaluate page "p => window.__demoMove(p.x,p.y)" {"x" x "y" y})
        (.move (.mouse page) x y)
        (.waitForTimeout page 16)))
    (reset! position destination)
    (swap! events conj (assoc destination :type :pointer :at (- (now) base)))))

(defn action! [page action position events base]
  (case (:op action)
    :goto (do (.navigate page (:url action)) (install-pointer! page))
    :checkpoint (.waitForTimeout page 300)
    :wait-visible (.waitFor (.locator page (:selector action))
                            (doto (Locator$WaitForOptions.) (.setState WaitForSelectorState/VISIBLE)))
    :wait-text (do
                 (.waitForFunction page
                   "a => { const e=document.querySelectorAll(a.selector); return e.length===1 && e[0].textContent.includes(a.text); }"
                   {"selector" (:selector action) "text" (:text action)})
                 nil)
    (let [[loc destination] (target! page (:selector action))]
      (move! page position destination events base)
      (case (:op action)
        :hover (.hover loc)
        :fill (do
                (core/require! (not (.evaluate loc "e => e.tagName === 'INPUT' && e.type === 'password'"))
                               "Password fields cannot be filled during capture; authenticate outside the recording" {})
                (.fill loc (:text action)))
        :click (do (.evaluate page "p => window.__demoPulse(p.x,p.y)" {"x" (:x destination) "y" (:y destination)})
                   (.click loc)))))
  (swap! events conj {:type (:op action) :selector (:selector action) :at (- (now) base)}))

(defn marker! [page id]
  ;; A reserved 16px strip is cropped out of exports. Scene boundaries come
  ;; from these encoded frames, not the time at which Java sent a command.
  (.evaluate page
    "c => { let e=document.getElementById('__demo_sync'); if(!e){e=document.createElement('div');e.id='__demo_sync';document.documentElement.append(e);} Object.assign(e.style,{position:'fixed',left:'0',bottom:'0',width:'100vw',height:'16px',pointerEvents:'none',zIndex:2147483647,background:'rgb('+c.join(',')+')'}); }"
    (mapv double [(+ 16 (* 28 (mod id 8))) (+ 16 (* 28 (quot id 8))) 240]))
  (.waitForTimeout page 180))

(defn decode-marker [[r g b]]
  (let [x (Math/round (/ (- r 16) 28.0)) y (Math/round (/ (- g 16) 28.0))]
    (when (and (< (abs (- b 240)) 10) (<= 0 x 7) (<= 0 y 7)
               (< (abs (- r (+ 16 (* x 28)))) 10)
               (< (abs (- g (+ 16 (* y 28)))) 10))
      (+ x (* y 8)))))
(defn boundaries [video height ids]
  (let [bytes (dio/binary! "ffmpeg" "-v" "error" "-i" video "-vf"
                           (str "fps=30,crop=16:16:0:" height ",scale=1:1")
                           "-pix_fmt" "rgb24" "-f" "rawvideo" "-")
        labels (mapv decode-marker (partition 3 (map #(bit-and % 255) bytes)))
        found (reduce (fn [out [i [a b c]]]
                        (if (and (some? a) (= a b c) (not (contains? out a)))
                          (assoc out a (/ i 30.0)) out))
                      {} (map-indexed vector (partition 3 1 labels)))]
    (doseq [id ids]
      (core/require! (contains? found id) "Recording is missing a scene sync marker" {:marker id}))
    found))

(defn capture! [project out]
  (core/validate-project project)
  (core/require! (<= (count (:scenes project)) 62) "Capture supports at most 62 scenes" {})
  (core/require! (= :goto (get-in project [:scenes 0 :actions 0 :op]))
                 "First scene must start with :goto; initial navigation occurs before capture content" {})
  (dio/fresh-dir! out)
  (let [{:keys [width height]} (get project :viewport core/default-viewport)
        events (atom []) position (atom {:x 100 :y 100})]
    (with-open [pw (Playwright/create (doto (Playwright$CreateOptions.)
                                       (.setEnv {"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" "1"})))]
      (let [opts (doto (BrowserType$LaunchOptions.) (.setHeadless true))
            _ (if-let [executable (get-in project [:browser :executable])]
                (.setExecutablePath opts (path executable))
                (.setChannel opts "chrome"))]
        (with-open [browser (.launch (.chromium pw) opts)]
          (let [options (doto (Browser$NewContextOptions.)
                          (.setAcceptDownloads false)
                          (.setViewportSize width (+ height 16))
                          (.setRecordVideoSize width (+ height 16))
                          (.setRecordVideoDir (path (str out "/raw"))))
                _ (when-let [state (get-in project [:browser :storage-state])]
                    (.setStorageStatePath options (path state)))
                context (.newContext browser options)]
            (try
              (let [page (.newPage context)
                    _ (.setDefaultTimeout page 15000)
                    _ (.navigate page (get-in project [:scenes 0 :actions 0 :url]))
                    _ (install-pointer! page)
                    _ (marker! page 1)
                    base (now)
                    scenes
                    (mapv (fn [i scene]
                            (let [start (- (now) base)
                                  actions (if (zero? i) (rest (:actions scene)) (:actions scene))]
                              (doseq [action actions]
                                (action! page action position events base)
                                (when (= :goto (:op action)) (marker! page (inc i))))
                              (let [focus (when-let [sel (:focus-selector scene)]
                                            (let [[_ p] (target! page sel)]
                                              {:x (/ (:x p) width) :y (/ (:y p) height)}))]
                                (.waitForTimeout page 250)
                                (marker! page (+ i 2))
                                {:id (:id scene) :wall-start start :wall-end (- (now) base)
                                 :checkpoint? (= :checkpoint (:op (last (:actions scene))))
                                 :actions (:actions scene) :focus focus})))
                          (range) (:scenes project))
                    video (.video page)]
                (.close context)
                (let [source (str (.path video))
                      marks (boundaries source height (range 1 (+ 2 (count scenes))))
                      data {:version 1 :width width :height height
                            :source (.getCanonicalPath (io/file source))
                            :scenes (mapv (fn [i s] (assoc s :start (marks (inc i)) :end (marks (+ i 2))))
                                          (range) scenes)
                            :events events :event-clock :monotonic-relative-to-first-marker}]
                  (dio/write-edn! (str out "/capture.edn") (assoc data :events @events))
                  (dio/write-edn! (str out "/project.edn") (privacy/project-copy project))
                  (assoc data :events @events)))
              (finally (.close context)))))))))
