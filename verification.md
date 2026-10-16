# Implementation verification

## Passed

- `clojure -M:test`: 17 tests, 179 assertions, zero failures/errors.
- `clojure -M:smoke work/acceptance-v1`: passed with installed Chrome,
  FFmpeg, and the already-running local Kokoro service.
- Real fixture actions: edit the name, open visibility options, choose Team,
  save, and wait for the exact visible confirmation.
- Initial export: approximately 13.34 seconds of H.264 video with AAC narration.
- Revised export: approximately 17.82 seconds after lengthening the opening
  explanation and changing its zoom; no browser recapture.
- SHA-256 of the source stayed unchanged:
  `4b6c2b2359b02551674d82c07f5b1bc977288c2b8bde0ea59e162c6d67edce5c`.
- Both outputs decoded without FFmpeg errors. Audio/video durations differed
  by less than 0.1 seconds. Volume analysis found non-silent audio.
- Inspected extracted final frames for all three scenes. The edited name,
  Team visibility, and saved confirmation appear. Captions remain unscaled
  by zoom, and the sync strip does not appear in exports.
- Synthetic regression proves a checkpoint hold retains the completed
  on-screen state rather than an earlier frame. The initial wall-clock-based
  prototype failed this visual requirement; encoded marker bounds replaced it.

## Zoom correction

- User playback review identified shaky zooms. A moving-target regression
  measured native-resolution camera motion error at 1.106 pixels/frame RMS.
- A 4× YUV444 working image reduced that error to 0.120 pixels/frame: **89.2%**
  lower, with the same camera curve. `clojure -M:test-zoom` requires at least
  a 50% reduction to guard against regression.
- Re-rendered the same source and measured WAV cues into
  `work/acceptance-v1/render-v3-smooth/demo.mp4`; no recapture or speech
  regeneration. Output dimensions, narration, and intended timing remain unchanged.
- This quantitative test measures pixel jitter, not subjective pacing or
  full perceptual smoothness; user playback review remains necessary.

## Narration correction

- User playback review identified pauses within sentences. The initial
  eight-word synthesis units introduced artificial boundaries: silence analysis
  of the opening narration measured 0.678s and 0.663s gaps at those joins.
- Synthesis now uses complete sentences. Caption line wrapping no longer
  controls speech segmentation. Tests cover long sentences, punctuation,
  decimal values, and text without closing punctuation.
- Re-rendered unchanged footage with regenerated sentence audio into
  `work/acceptance-v1/render-v4-speech/demo.mp4`. The smooth zoom correction
  remains active. Final MP4 decoding passed, and the long two-line caption was
  inspected. Natural sentence/phrase pauses remain; listening review is still
  required. The installed speech API does not expose word timestamps.

## Larger viewport

- Capture/export now default to 1920×1200, configurable through project
  `:viewport`. Explicit dimension changes require recapture; legacy capture
  manifests remain renderable at their recorded size.
- `clojure -M:smoke work/acceptance-large-v1` passed, including real browser
  recapture, sentence-based narration, revised zooms, unchanged-source checks,
  marker regression, media decoding, and audio/video duration checks.
- The revised 1920×1200 demo lasts approximately 16.87 seconds:
  `work/acceptance-large-v1/render-v2/demo.mp4`.
- Inspected opening-scene and saved-result frames. Caption sizing scales with
  output dimensions; the larger viewport is real capture, not an upscale.

## Optional zoom (superseded by persistent camera tracks)

- Zoom now defaults off, regardless of stored focus targets. Set project
  `:zoom? true` to opt in. Disabled zoom bypasses both camera transforms and
  supersampling; existing recordings do not need recapture.
- Re-rendered `work/acceptance-large-v1/render-no-zoom/demo.mp4` from the
  existing 1920×1200 capture. Tests cover default-off, explicit false/true,
  captured and edited focus, and invalid configuration.

## Agent-directed cuts

- Scenes accept explicit, ordered, nonoverlapping `:cuts` in source-scene
  seconds. Cuts affect footage only, keep the last frame, and leave raw
  capture untouched. The pure planner rebuilds playback lengths and holds.
- `clojure -M:test-cuts work/cut-regression-v1` passed: a three-second
  action/wait/result source became two seconds; pixel checks verified the
  retained action and completed result, with the intervening wait removed.
- `clojure -M:smoke work/acceptance-cuts-v1` passed, including default-off
  zoom and explicit opt-in on the revised render, after the cut pipeline change.
- No heuristic selects cuts. Narration may still require a checkpoint hold
  after cutting footage; spoken claims need agent review.

## Persistent camera tracks

- Replaced the per-scene automatic curve and `:zoom?` flag with an explicit
  project `:camera` track. Targets hold until another instruction changes
  them, including across scenes. Missing/empty tracks leave the view stationary.
- Removed implicit zoom levels from captured focus metadata. Old `:zoom?`
  and scene-level `:focus` configurations fail with migration guidance;
  existing source recordings remain reusable.
- Pure tests cover default-off behavior, sustained zoom, return to full view,
  immediate/interrupted transitions, source-cut remapping, narration holds,
  ordering, invalid anchors, and migration errors.
- `clojure -M:test-zoom` passed with the new camera model. Rendered centroid
  comparisons prove that held frames stay stationary and splitting a render
  at either a hold or an active transition does not reset the camera.
  Supersampling reduced measured motion error by 86.8% on this new trajectory.
- `clojure -M:test-cuts work/cut-camera-regression-v1` passed.
- Re-rendered original footage and cached sentence audio into
  `work/camera-persistent-v1/demo.mp4`. The camera zooms in during the first
  scene, holds through the next scene, and explicitly zooms out in the last.
  Full video decoding and sampled hold/zoom-out frame inspection passed.
- The current live smoke run reached speech generation but could not finish:
  the local Kokoro service was unavailable and no launcher was configured.
  No service was installed or restarted. Cached speech enabled the real-footage
  render above; this is not a fresh live speech-service acceptance run.

## Modern fixture and inline README video

- Redesigned the bundled fixture as Forma, a responsive project settings UI
  with a workspace sidebar, live preview, accessible access picker, and
  asynchronous save feedback. Existing capture selectors and success text
  remain compatible.
- `work/acceptance-forma-v1/` passed real browser capture, marker regression,
  two renders, media decoding, duration checks, and unchanged-source checks.
  Narration used cached sentence WAVs because Kokoro was unavailable; the
  acceptance manifest explicitly records that fact.
- Inspected sampled opening and result frames. Published the revised demo as
  a GitHub attachment and verified that GitHub's Markdown renderer emits a
  video element with playback controls.
- The README uses the stable, standalone attachment URL, not a release
  download URL or a temporary signed media URL.

## Longer launch walkthrough

- `examples/launch.edn` drives nine scenes with real task creation,
  completion, filtering, browser-local save, and reload.
- `clojure -M:test-fixture`: 26 browser assertions passed, including
  persistence, denied storage, corrupt saved data, and three viewport widths.
  Existing unit tests: 179 assertions passed.
- Fresh capture and local Kokoro narration produced
  `work/launch-v1/render/demo.mp4`. Revised camera framing reused that capture
  and its sentence audio in `work/launch-v1/render-v2/demo.mp4`.
- Final export: 1920×1200 H.264/AAC; video 54.703060 seconds,
  audio 54.711708 seconds; FFmpeg decoding passed.
- Inspected a frame from each scene and corrected the camera framing to keep
  checklist status clear of captions. The saved and restored states show
  three tasks with one complete.
- Audio tracks and timing were checked structurally, not by listening.
  Team access remains a fixture setting, not a backend authorization system.

## Unlabelled checkpoint holds

- Removed the Paused overlay without changing checkpoint timing or captions.
- `work/launch-v1/render-v4/demo.mp4` reuses the existing capture and sentence
  audio; video/audio durations remain 54.703060/54.711708 seconds.
- Unit tests and the rendered checkpoint regression pass. The regression
  checks both the completed state and the absence of an upper-left overlay.
- Final MP4 decoding and a sampled hold frame passed inspection.

## Security and privacy cleanup

- Reviewed tracked files and all 42 historical file blobs present before
  this change. No common credential, email, or personal home-path signatures
  matched text content. This was a targeted pattern scan, not an exhaustive
  secret/dependency audit; see `SECURITY.md`.
- Unit tests: 200 assertions; real-browser tests: 28 assertions, including
  refusal to fill a password input. All passed.
- Confirmed that the real CLI rejects a credential-bearing URL without
  printing its credential sentinel. Confirmed owner-only POSIX permissions
  for new outputs; tightened the existing ignored `work/` directory to 0700.
- Zoom regression passed. Fresh capture and two renders passed at
  `work/privacy-acceptance-v2/`, including media decoding, unchanged source,
  hold behavior, and absent pause labels.
- Kokoro was unavailable for the initial live smoke attempt. The successful
  run reused exact saved sentence audio; its manifest records that limitation.
- Git author metadata and previously published attachments were not rewritten.
  No credentials were identified for rotation.

## Manual authentication setup

- `auth project.edn new-auth-directory` opens a separate visible browser,
  requests manual login, verifies transferred state in a fresh context, and
  saves only after a second explicit confirmation. Neither context records.
- 206 unit assertions and 43 real-browser assertions pass. Auth tests cover
  cookies, local storage, IndexedDB, absent session-storage transfer, refreshed
  state export, cancellation/EOF, no overwrite, no video, and POSIX file mode 0600.
- Browser automation tests run headless against a local fixture with synthetic
  state. Actual manual login, SSO/MFA, and device-bound authentication against
  a real service still require user verification.
- No personal browser debugging connection is used. The command does not
  automatically decide whether the user is authenticated.

## Independent source and edit (version 2)

- Shooting recipes now put actions in `:capture :scenes`. Separate edit files
  reference recorded IDs through `:source`; each edit instance owns narration,
  cuts, and camera instructions. Reorder, omit, and reuse footage without
  executing browser actions. Version-1 projects/capture manifests still work.
- 244 unit assertions and 43 real-browser assertions pass. Tests cover repeated
  sources with unique IDs, omissions, unknown/duplicate IDs, local camera/cut
  remapping, checkpoint timing, and exact version-1 migration equivalence.
  Authentication roundtrip now also exercises a capture-only version-2 recipe.
- `work/edit-v2-regression-v2/` renders two edits from synthetic red/blue
  footage and verifies output frame colors/order, reuse, cuts, media decoding,
  and unchanged source hash. Synthetic tone audio replaces speech in this test.
  The edit ID `demo` also verifies that intermediate clips cannot collide
  with the final `demo.mp4`.
- Fresh real-Chrome capture at `work/source-edit-acceptance-v1/` used the new
  shooting recipe. Its edit played save/name/save from name/visibility/save
  footage. H.264/AAC decoding passed, and the source SHA256 stayed unchanged.
  This run reused cached sentence audio, not live Kokoro.
- Existing zoom and cut regressions pass. Full-motion/listening review of the
  new example edit remains a human check; this is not a multi-track editor.

## Limits of this evidence

- Speech was generated and measured, but not listened to during implementation.
- Frame review sampled the final output; it was not a full-motion playback
  review. Captions can cover secondary controls. Automatic collision avoidance
  is not implemented.
- Kokoro reuse was tested live. Owned startup, timeout, process-exit handling,
  and cleanup were exercised with injected lifecycle boundaries, not by
  restarting the user's Kokoro installation.
- Unit tests exercise only the bundled contract. Real authenticated apps,
  navigation across origins, popups, and complex layouts need separate checks.
- The first Playwright invocation downloaded its browser bundle to the user's
  Playwright cache. Capture now disables automatic browser downloads; the
  README documents explicit recording-helper setup.
- No audio was sent to a cloud service; no output was uploaded or committed.

Artifacts remain under the ignored `work/acceptance-v1/` directory.
