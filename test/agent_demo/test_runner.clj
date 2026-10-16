(ns agent-demo.test-runner
  (:require [clojure.test :as t] [agent-demo.core-test] [agent-demo.camera-test]))
(defn -main [& _]
  (let [result (t/run-tests 'agent-demo.core-test 'agent-demo.camera-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
