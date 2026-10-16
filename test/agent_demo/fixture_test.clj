(ns agent-demo.fixture-test
  "Real-browser checks for the bundled demo app; no Kokoro required."
  (:require [clojure.test :refer [deftest is run-tests]]
            [agent-demo.main :as main]
            [agent-demo.capture :as capture]
            [agent-demo.auth :as auth]
            [agent-demo.core :as core]
            [clojure.java.io :as io])
  (:import [com.microsoft.playwright Playwright Playwright$CreateOptions BrowserType$LaunchOptions]))

(deftest launch-workflow
  (let [server (main/fixture! 0)]
    (try
      (with-open [pw (Playwright/create (doto (Playwright$CreateOptions.)
                                         (.setEnv {"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" "1"})))
                  browser (.launch (.chromium pw) (doto (BrowserType$LaunchOptions.)
                                                   (.setChannel "chrome") (.setHeadless true)))]
        (let [page (.newPage browser)
              loc #(.locator page %)
              click #(.click (loc %))
              text #(.textContent (loc %))
              evaluate #(.evaluate page %)
              add-task (fn [title] (.fill (loc "#task-name") title) (click "#add-task"))]
          (.navigate page (str "http://127.0.0.1:" (.getPort (.getAddress server))))
          (doseq [[w h] [[1920 1200] [1280 800] [640 800]]]
            (.setViewportSize page w h)
            (is (evaluate "document.documentElement.scrollWidth <= innerWidth") (str "Details width " w)))
          (.fill (loc "#name") "Spring launch")
          (is (= "Spring launch" (text "#preview-name")))
          (click "#options")
          (is (= "true" (.getAttribute (loc "#options") "aria-expanded")))
          (.press (loc "#options") "Escape")
          (is (.isHidden (loc "#menu")))
          (click "#options") (click "#team")
          (is (= "Team" (text "#visibility")))
          (click "#tasks-tab")
          (is (= "Spring launch" (text "#plan-name")))
          (add-task "Finish the landing page")
          (add-task "Review the launch copy")
          (add-task "Announce the launch to the team")
          (is (= "0 of 3 tasks complete" (text "#task-progress")))
          (doseq [[w h] [[1920 1200] [1280 800] [640 800]]]
            (.setViewportSize page w h)
            (is (evaluate "document.documentElement.scrollWidth <= innerWidth") (str "Checklist width " w)))
          (.setViewportSize page 1920 1200)
          (click "#task-1-toggle")
          (is (= "1 of 3 tasks complete" (text "#task-progress")))
          (click "#filter-open")
          (is (= 2 (.count (loc "#task-list li"))))
          (is (= 0 (.count (loc "#task-1-toggle"))))
          (click "#filter-all")
          (is (.isChecked (loc "#task-1-toggle")))
          (click "#save-plan")
          (is (.isDisabled (loc "#add-task")))
          (.waitForFunction page "document.querySelector('#plan-result').textContent === 'Saved Spring launch — 3 tasks, 1 complete.'")
          (is (not (.isDisabled (loc "#add-task"))))
          (.reload page)
          (is (= "Restored Spring launch — Team" (text "#result")))
          (click "#tasks-tab")
          (is (= "1 of 3 tasks complete" (text "#task-progress")))
          (is (.isChecked (loc "#task-1-toggle")))
          (is (= 3 (.count (loc "#task-list li"))))
          ;; A storage failure must not masquerade as a successful save.
          (evaluate "() => { Storage.prototype.setItem = () => { throw new Error('Storage unavailable'); }; }")
          (add-task "An unsaved task")
          (click "#save-plan")
          (.waitForFunction page "document.querySelector('#plan-result').textContent.includes('Could not save')")
          (is (not (.isDisabled (loc "#save-plan"))))
          (.reload page)
          (click "#tasks-tab")
          (is (= 3 (.count (loc "#task-list li"))))
          ;; Treat corrupt stored data as a fresh fixture, without script injection.
          (evaluate "localStorage.setItem('forma-demo-project-v1', '{broken')")
          (.reload page)
          (is (= "Untitled project" (.inputValue (loc "#name"))))
          (click "#tasks-tab")
          (is (= 0 (.count (loc "#task-list li"))))
          ;; Authentication belongs outside the recorded action sequence.
          (.setContent page "<input id='login-password' type='password'>")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Password fields"
                (capture/action! page {:op :fill :selector "#login-password" :text "test-only"}
                                 (atom {:x 0 :y 0}) (atom []) (capture/now))))
          (is (= "" (.inputValue (loc "#login-password"))))))
      (finally (.stop server 0)))))

(deftest manual-auth-roundtrip
  (let [server (main/fixture! 0)
        root (.toFile (java.nio.file.Files/createTempDirectory
                       "agent-demo-auth-" (make-array java.nio.file.attribute.FileAttribute 0)))
        project {:version 2
                 ;; Setup must ignore any old session and begin isolated.
                 :browser {:storage-state "/nonexistent/old-session.json"}
                 :capture {:scenes [{:id "demo"
                                     :actions [{:op :goto :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}]}]}}
        phases (atom [])]
    (try
      (let [out (str root "/session")
            state (auth/auth! project out
                    {:headless? true
                     :confirm-fn
                     (fn [phase page]
                       (swap! phases conj phase)
                       (is (nil? (.video page)) "Login and verification are never recorded")
                       (case phase
                         :login
                         (do
                           (is (nil? (.evaluate page "localStorage.getItem('demo-account')")))
                           (.evaluate page "() => { document.cookie='demo_auth=fixture; path=/'; localStorage.setItem('demo-account','fixture'); sessionStorage.setItem('session-only','not-transferable'); }")
                           (.evaluate page "async () => { await new Promise((resolve,reject) => { const r=indexedDB.open('demo-auth',1); r.onupgradeneeded=()=>r.result.createObjectStore('session'); r.onerror=()=>reject(r.error); r.onsuccess=()=>{ const db=r.result; const t=db.transaction('session','readwrite'); t.objectStore('session').put('fixture','token'); t.oncomplete=()=>{db.close();resolve();}; t.onerror=()=>reject(t.error); }; }); }"))
                         :verify
                         (do
                           (is (= "fixture" (.evaluate page "localStorage.getItem('demo-account')")))
                           (is (.contains (.evaluate page "document.cookie") "demo_auth=fixture"))
                           (is (nil? (.evaluate page "sessionStorage.getItem('session-only')")))
                           (is (= "fixture" (.evaluate page "async () => await new Promise((resolve,reject) => { const r=indexedDB.open('demo-auth',1); r.onerror=()=>reject(r.error); r.onsuccess=()=>{const db=r.result; const q=db.transaction('session').objectStore('session').get('token'); q.onsuccess=()=>{db.close();resolve(q.result);}; q.onerror=()=>reject(q.error);}; })")))
                           (.evaluate page "localStorage.setItem('demo-account','fixture-refreshed')"))))})]
        (is (= [:login :verify] @phases))
        (is (.contains (slurp state) "fixture-refreshed"))
        (is (= #{"storage-state.json"} (set (.list (io/file out)))))
        (when (contains? (set (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault))) "posix")
          (is (= (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------")
                 (java.nio.file.Files/getPosixFilePermissions (capture/path state)
                   (make-array java.nio.file.LinkOption 0)))))
        (is (thrown? Exception (auth/save-state! state "must not replace")))
        (is (.contains (slurp state) "fixture-refreshed")))
      (let [out (str root "/cancelled")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
              (auth/auth! project out
                {:headless? true
                 :confirm-fn (fn [phase _]
                               (when (= phase :verify)
                                 (core/require! false "Setup cancelled" {})))})))
        (is (empty? (.list (io/file out)))))
      (finally
        (.stop server 0)
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(deftest configurable-completion-waits
  (with-open [pw (Playwright/create (doto (Playwright$CreateOptions.)
                                    (.setEnv {"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" "1"})))
              browser (.launch (.chromium pw) (doto (BrowserType$LaunchOptions.)
                                               (.setChannel "chrome") (.setHeadless true)))]
    (let [page (.newPage browser)
          events (atom [])
          position (atom {:x 0 :y 0})
          delayed-result! (fn [delay]
                            (.setContent page "<p id='result' hidden>Loading</p>")
                            (.evaluate page
                              "delay => { const result = document.querySelector('#result'); setTimeout(() => { result.textContent = 'Ready'; result.hidden = false; }, delay); }"
                              (double delay)))]
      ;; Reproduce the reported boundary with real asynchronous browser work.
      (capture/configure-timeouts! page {})
      (delayed-result! 16000)
      (is (thrown? com.microsoft.playwright.TimeoutError
            (capture/action! page {:op :wait-text :selector "#result" :text "Ready"}
                             position events (capture/now))))
      (capture/configure-timeouts! page {:browser {:timeout-ms 20000}})
      (delayed-result! 16000)
      (capture/action! page {:op :wait-text :selector "#result" :text "Ready"}
                       position events (capture/now))
      (is (= "Ready" (.textContent (.locator page "#result"))))
      ;; Visibility waits use the same setting, rather than a separate 15s cap.
      (capture/configure-timeouts! page {:browser {:timeout-ms 2000}})
      (delayed-result! 300)
      (capture/action! page {:op :wait-visible :selector "#result"}
                       position events (capture/now))
      (is (.isVisible (.locator page "#result"))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'agent-demo.fixture-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
