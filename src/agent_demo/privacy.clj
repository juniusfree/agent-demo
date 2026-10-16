(ns agent-demo.privacy)

(defn error
  "Mark a static, application-authored message as safe for terminal output.
   Never interpolate user data into this message. Exception data stays private."
  [message data]
  (ex-info message (assoc data ::public-message true)))

(defn error-summary [exception]
  (if (::public-message (ex-data exception))
    (.getMessage exception)
    "Operation failed. Check the input files, browser selectors, and local dependencies. Diagnostic details are suppressed to avoid exposing private data."))

(defn project-copy
  "Keep editable presentation/actions, not capture authentication or launch config.
   This is still a private artifact: actions and narration may contain PII."
  [project]
  (cond-> (dissoc project :browser)
    (map? (:kokoro project)) (update :kokoro dissoc :command)))
