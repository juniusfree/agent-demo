(ns agent-demo.auth
  "Manual login and fresh-context verification, without recording."
  (:require [agent-demo.core :as core]
            [agent-demo.io :as dio]
            [agent-demo.capture :as capture])
  (:import [com.microsoft.playwright Playwright Playwright$CreateOptions
            BrowserType$LaunchOptions Browser$NewContextOptions BrowserContext$StorageStateOptions]
           [java.nio.file Files FileSystems StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.nio.channels Channels]))

(defn confirm! [phase _page]
  (println (case phase
             :login "Sign in manually in the separate browser. When the app is ready, type verify here. Anything else cancels."
             :verify "A fresh browser context is now open. Check that you are still signed in to the intended demo account WITHOUT signing in again. If you see a login page, cancel. Type save to keep this session. Anything else cancels."))
  (flush)
  (core/require! (= (case phase :login "verify" :verify "save") (read-line))
                 "Authentication setup cancelled; no session file saved" {}))

(defn save-state! [file state]
  (let [attributes (if (contains? (set (.supportedFileAttributeViews (FileSystems/getDefault))) "posix")
                     [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rw-------"))]
                     [])]
    (with-open [channel (Files/newByteChannel (capture/path file)
                         #{StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE}
                         (into-array FileAttribute attributes))
                writer (Channels/newWriter channel "UTF-8")]
      (.write writer ^String state)))
  file)

(defn auth!
  "The callback and headless option are test boundaries; the CLI always uses
   a visible browser and explicit human confirmation. Never records video."
  ([project out] (auth! project out {}))
  ([input out {:keys [confirm-fn headless?] :or {confirm-fn confirm! headless? false}}]
   (let [project (core/capture-project input)]
     (core/require! (= :goto (get-in project [:scenes 0 :actions 0 :op]))
                    "Authentication setup needs a first :goto action for the app URL" {})
     (dio/fresh-dir! out)
     (let [url (get-in project [:scenes 0 :actions 0 :url])
           state-options (doto (BrowserContext$StorageStateOptions.) (.setIndexedDB true))]
       (with-open [pw (Playwright/create (doto (Playwright$CreateOptions.)
                                          (.setEnv {"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" "1"})))]
         (let [launch (doto (BrowserType$LaunchOptions.) (.setHeadless (boolean headless?)))
               _ (if-let [executable (get-in project [:browser :executable])]
                   (.setExecutablePath launch (capture/path executable))
                   (.setChannel launch "chrome"))]
           (with-open [browser (.launch (.chromium pw) launch)]
             (let [state
                   (with-open [context (.newContext browser (doto (Browser$NewContextOptions.)
                                                             (.setAcceptDownloads false)))]
                     (let [page (.newPage context)]
                       (capture/configure-timeouts! page project)
                       (.navigate page url)
                       (confirm-fn :login page)
                       (.storageState context state-options)))]
               ;; Do not trust a successful login in the original context: session
               ;; storage and some device-bound sessions cannot be transferred.
               (with-open [context (.newContext browser (doto (Browser$NewContextOptions.)
                                                         (.setAcceptDownloads false)
                                                         (.setStorageState state)))]
                 (let [page (.newPage context)]
                   (capture/configure-timeouts! page project)
                   (.navigate page url)
                   (confirm-fn :verify page)
                   (save-state! (str out "/storage-state.json")
                                (.storageState context state-options))))))))))))
