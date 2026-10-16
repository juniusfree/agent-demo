(ns agent-demo.capture
  (:require [agent-demo.core :as core] [agent-demo.cursor :as cursor] [agent-demo.io :as dio] [clojure.java.io :as io] [agent-demo.privacy :as privacy])
  (:import [com.microsoft.playwright Playwright Playwright$CreateOptions BrowserType$LaunchOptions Browser$NewContextOptions
            Locator$WaitForOptions]
           [com.microsoft.playwright.options WaitForSelectorState BindingCallback]
           [java.nio.file Paths]))

(defn now [] (/ (System/nanoTime) 1e9))
(defn path [s] (Paths/get (str s) (make-array String 0)))
(defn configure-timeouts! [page project]
  (let [timeout (double (core/browser-timeout-ms project))]
    (.setDefaultTimeout page timeout)
    (.setDefaultNavigationTimeout page timeout))
  page)

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
  (let [{sx :x sy :y} @position {dx :x dy :y} destination]
    (swap! events conj (assoc @position :type :pointer :at (- (now) base)))
    (doseq [step (range 1 19)]
      (let [t (/ step 18.0) e (* t t (- 3 (* 2 t)))
            x (+ sx (* (- dx sx) e)) y (+ sy (* (- dy sy) e))]
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
        :click (.click loc))))
  (swap! events conj {:type :action :op (:op action) :selector (:selector action) :at (- (now) base)}))

(defn marker! [page id]
  ;; A reserved 16px strip is cropped out of exports. Scene boundaries come
  ;; from these encoded frames, not the time at which Java sent a command.
  (let [stamp (now)]
    (.evaluate page
    "c => { let e=document.getElementById('__demo_sync'); if(!e){e=document.createElement('div');e.id='__demo_sync';document.documentElement.append(e);} Object.assign(e.style,{position:'fixed',left:'0',bottom:'0',width:'100vw',height:'16px',pointerEvents:'none',zIndex:2147483647,background:'rgb('+c.join(',')+')'}); }"
    (mapv double [(+ 16 (* 28 (mod id 8))) (+ 16 (* 28 (quot id 8))) 240]))
    (.waitForTimeout page 180)
    stamp))

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

(defn capture! [input out]
  (let [project (core/capture-project input)]
    (core/require! (<= (count (:scenes project)) 62) "Capture supports at most 62 scenes" {})
    (core/require! (= :goto (get-in project [:scenes 0 :actions 0 :op]))
                   "First scene must start with :goto; initial navigation occurs before capture content" {})
    (dio/fresh-dir! out)
    (let [{:keys [width height]} (get project :viewport core/default-viewport)
          events (atom []) position (atom {:x 100 :y 100}) clock-base (atom nil)]
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
              (.addInitScript context (slurp (io/resource "pointer.js")))
              (try
                (let [page (.newPage context)
                      _ (.exposeBinding page "__demoRecordClick"
                                        (reify BindingCallback
                                          (call [_ _ args]
                                            (when-let [base @clock-base]
                                              (let [p (aget args 0)
                                                    at (- (now) base)
                                                    actual {:x (get p "x") :y (get p "y")}]
                                                ;; Locator.click may reposition after a layout change.
                                                ;; Keep idle motion still, then jump to the real hit point.
                                                (when-not (and (== (:x actual) (:x @position))
                                                               (== (:y actual) (:y @position)))
                                                  (swap! events into [(assoc @position :type :pointer :at at)
                                                                      (assoc actual :type :pointer :at at)])
                                                  (reset! position actual))
                                                (swap! events conj (assoc actual :type :click :at at))))
                                            nil)))
                      _ (configure-timeouts! page project)
                      _ (.navigate page (get-in project [:scenes 0 :actions 0 :url]))
                      _ (install-pointer! page)
                      _ (.move (.mouse page) 100.0 100.0)
                      base (marker! page 1)
                      _ (reset! clock-base base)
                      _ (swap! events conj {:type :pointer :at 0 :x 100 :y 100})
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
                                  (let [end (- (marker! page (+ i 2)) base)]
                                    {:id (:id scene) :wall-start start :wall-end end
                                     :checkpoint? (= :checkpoint (:op (last (:actions scene))))
                                     :actions (:actions scene) :focus focus}))))
                            (range) (:scenes project))
                      video (.video page)]
                  (.close context)
                  (let [source (str (.path video))
                        marks (boundaries source height (range 1 (+ 2 (count scenes))))
                        anchors (into [{:wall 0 :video (marks 1)}]
                                      (map-indexed (fn [i scene] {:wall (:wall-end scene) :video (marks (+ i 2))}) scenes))
                        aligned (mapv #(update % :at (partial cursor/align-time anchors)) @events)
                        data {:version 1 :width width :height height
                              :source (.getCanonicalPath (io/file source))
                              :scenes (mapv (fn [i s] (assoc s :start (marks (inc i)) :end (marks (+ i 2))))
                                            (range) scenes)
                              :events aligned :event-clock :source-video :clock-anchors anchors
                              :cursor {:clock :source-video
                                       :events (mapv #(select-keys % [:type :at :x :y])
                                                     (filter #(#{:pointer :click} (:type %)) aligned))}}]
                    (cursor/validate-events data)
                    (dio/write-edn! (str out "/capture.edn") data)
                    (dio/write-edn! (str out "/project.edn") (privacy/project-copy input))
                    data))
                (finally (.close context))))))))))
