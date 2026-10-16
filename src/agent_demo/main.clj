(ns agent-demo.main
  (:require [agent-demo.auth :as auth] [agent-demo.capture :as capture] [agent-demo.render :as render]
            [agent-demo.io :as dio] [agent-demo.privacy :as privacy] [agent-demo.core :as core] [clojure.java.io :as io])
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress]))

(defn fixture! [port]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)
        body (.getBytes (slurp (io/resource "fixture.html")) "UTF-8")]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (.set (.getResponseHeaders exchange) "Content-Type" "text/html; charset=utf-8")
          (.sendResponseHeaders exchange 200 (count body))
          (with-open [out (.getResponseBody exchange)] (.write out body)))))
    (.start server)
    server))

(def usage "Commands:
  clojure -M:run fixture [port]
  clojure -M:run validate project.edn
  clojure -M:run auth project.edn new-auth-directory
  clojure -M:run capture project.edn new-capture-directory
  clojure -M:run render project.edn capture-directory/capture.edn new-render-directory

Capture executes agent-authored :actions through the supplied adapter.
Render changes presentation without executing browser actions.")
(defn -main [& args]
  (try
    (case (first args)
      "fixture" (do (core/require! (<= 1 (count args) 2) usage {})
                    (let [port (Integer/parseInt (or (second args) "8799"))]
                      (fixture! port) (println (str "Fixture: http://127.0.0.1:" port))))
      "validate" (do (core/require! (= 2 (count args)) usage {})
                     (core/validate-project (dio/read-edn (second args))) (println "Valid project"))
      "auth" (do (core/require! (= 3 (count args)) usage {})
                 (let [state (auth/auth! (dio/read-edn (second args)) (nth args 2))]
                   (println "Private session saved. Set :browser :storage-state in your local project to:")
                   (prn state)
                   (println "Keep this file private. It grants access to the demo account.")))
      "capture" (do (core/require! (= 3 (count args)) usage {})
                    (capture/capture! (dio/read-edn (second args)) (nth args 2))
                    (println (str (nth args 2) "/capture.edn")))
      "render" (do (core/require! (= 4 (count args)) usage {})
                   (println (render/render! (dio/read-edn (second args))
                                            (dio/read-edn (nth args 2)) (nth args 3))))
      (println usage))
    (catch Exception e
      (binding [*out* *err*]
        (println "Error:" (privacy/error-summary e)))
      (System/exit 1))))
