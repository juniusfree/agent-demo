(ns agent-demo.fixture-test
  "Real-browser checks for the bundled demo app; no Kokoro required."
  (:require [clojure.test :refer [deftest is run-tests]]
            [agent-demo.main :as main])
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
          (is (= 0 (.count (loc "#task-list li"))))))
      (finally (.stop server 0)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'agent-demo.fixture-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
