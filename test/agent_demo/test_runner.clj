(ns agent-demo.test-runner
  (:require [clojure.test :as t] [agent-demo.core-test] [agent-demo.camera-test] [agent-demo.privacy-test] [agent-demo.edit-test] [agent-demo.cursor-test] [agent-demo.motion-blur-test]))
(defn -main [& _]
  (let [result (t/run-tests 'agent-demo.core-test 'agent-demo.camera-test 'agent-demo.privacy-test 'agent-demo.edit-test 'agent-demo.cursor-test 'agent-demo.motion-blur-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
