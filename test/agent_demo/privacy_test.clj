(ns agent-demo.privacy-test
  (:require [clojure.test :refer :all]
            [agent-demo.privacy :as privacy]
            [agent-demo.core :as core]
            [agent-demo.auth :as auth]
            [agent-demo.io :as dio]
            [clojure.java.io :as io])
  (:import [java.nio.file Files FileSystems]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(deftest terminal-errors-do-not-echo-input
  (is (= "Invalid input" (privacy/error-summary
                          (privacy/error "Invalid input" {:action {:text "private-value"}}))))
  (doseq [exception [(Exception. "private-value")
                     (ex-info "private-value" {:command ["private-value"]})]]
    (is (not (.contains (privacy/error-summary exception) "private-value"))))
  (try
    (dio/run! (str (System/getProperty "java.home") "/bin/java") "private-value")
    (is false "Expected subprocess failure")
    (catch clojure.lang.ExceptionInfo e
      (is (not (.contains (pr-str (ex-data e)) "private-value"))))))

(deftest saved-project-omits-operational-secrets
  (let [project {:version 1
                 :browser {:storage-state "/private/session.json" :executable "/private/chrome"}
                 :kokoro {:endpoint "http://127.0.0.1:8880" :voice "af_heart"
                          :command ["local-service" "--credential" "private-value"]}
                 :scenes [{:id "demo" :narration "A demo." :actions []}]}
        saved (privacy/project-copy project)]
    (is (nil? (:browser saved)))
    (is (nil? (get-in saved [:kokoro :command])))
    (is (= "af_heart" (get-in saved [:kokoro :voice])))
    (is (= (:scenes project) (:scenes saved)))
    (is (some? (:browser project)))
    (is (= {:version 1} (privacy/project-copy {:version 1})))))

(deftest navigation-rejects-embedded-credentials
  (doseq [url ["http://localhost:8799" "https://example.test/path?q=demo"]]
    (is (core/browser-url? url)))
  (doseq [url ["http://demo:private-value@example.test" "https://demo@example.test"
               "https:///missing-host" "file:///etc/passwd" "https://bad host" nil]]
    (is (not (core/browser-url? url)))))

(deftest auth-requires-explicit-confirmation
  (doseq [[phase input] [[:login "verify\n"] [:verify "save\n"]]]
    (binding [*in* (java.io.BufferedReader. (java.io.StringReader. input))
              *out* (java.io.StringWriter.)]
      (is (nil? (auth/confirm! phase nil)))))
  (doseq [[phase input] [[:login ""] [:login "save\n"] [:verify ""] [:verify "verify\n"]]]
    (binding [*in* (java.io.BufferedReader. (java.io.StringReader. input))
              *out* (java.io.StringWriter.)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
            (auth/confirm! phase nil))))))

(deftest private-output-directories
  (let [root (.toFile (Files/createTempDirectory "agent-demo-privacy-" (make-array FileAttribute 0)))]
    (try
      (let [output (dio/fresh-dir! (str root "/nested/capture"))]
        (is (.isDirectory output))
        (is (thrown? clojure.lang.ExceptionInfo (dio/fresh-dir! output)))
        (when (contains? (set (.supportedFileAttributeViews (FileSystems/getDefault))) "posix")
          (is (= (PosixFilePermissions/fromString "rwx------")
                 (Files/getPosixFilePermissions (.toPath output) (make-array java.nio.file.LinkOption 0))))))
      (finally
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))
