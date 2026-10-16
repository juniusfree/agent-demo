# Project format

Use this reference to author and revise EDN projects. Start with the
[agent guide](agent-guide.md) for setup, CLI commands, service ownership,
and verification. Run commands from the repository root.

[Back to README](../README.md)

## Project data

```clojure
{:version 1
 :viewport {:width 1920 :height 1200}
 :browser {:executable "/absolute/path/to/chromium"
           ;; Optional private Playwright storage-state file; never commit it.
           :storage-state "/private/path/session.json"}
 :kokoro {:endpoint "http://127.0.0.1:8880"
          :voice "af_heart"
          :command ["/absolute/path/to/preinstalled-kokoro" "serve"]
          :startup-timeout-ms 60000}
 :scenes
 [{:id "rename"
   :narration "Give the project a recognizable name."
   :focus-selector "#name"
   :actions [{:op :goto :url "http://localhost:3000"}
             {:op :fill :selector "#name" :text "Reviewer demo"}
             {:op :checkpoint}]}]}
```

Omit `:browser` to use installed Chrome with a fresh isolated context.
The sample Kokoro command is illustrative: configure the actual foreground
launcher for your installation. Do not configure a daemonizing launcher.

Supported action maps:

| Operation | Fields | Behavior |
| --- | --- | --- |
| `:goto` | `:url` | Navigate to HTTP(S) URL |
| `:click` | `:selector` | Smooth cursor movement, click pulse, real click |
| `:fill` | `:selector :text` | Move cursor, then fill the real field |
| `:hover` | `:selector` | Move cursor, then hover |
| `:wait-visible` | `:selector` | Wait for visibility |
| `:wait-text` | `:selector :text` | Wait for a unique CSS match containing text |
| `:checkpoint` | none | Mark the stable end of a scene as holdable |

Use unique CSS selectors. Actions/waits time out after 15 seconds. A failure
aborts capture; it does not claim a successful demo. Browser operations can
change real application data: isolation is **not** authorization to mutate
production.

## Revising without recapture

Copy/edit the project, retaining scene IDs, order, and actions. Narration,
cuts, and the camera track are presentation data; none requires recapture.
A scene's `:focus-selector` records normalized geometry in `capture.edn`
for the agent to reference. It does not move the camera automatically.

## Persistent camera track

Omit `:camera` (or use `:camera []`) for a stationary full view. To direct
the camera, add this **top-level** project field:

```clojure
:camera
[{:at {:scene "name" :time 0.4}
  :zoom 1.35
  :focus {:x 0.20 :y 0.26}
  :transition 0.6}

 {:at {:scene "save" :time 0.5}
  :zoom 1.0
  :transition 0.6}]
```

The camera transitions in, **holds the exact framing across scenes**, then
returns to full view only when instructed. There is no automatic zoom-out,
scene reset, or repeated in/out curve.

- `:at :time` is seconds from the named scene's **original captured start**.
  It must be within that source scene, including its end checkpoint.
- `:zoom` is 1–2.5. A value of 1 returns to the full viewport.
- Zoomed instructions require `:focus {:x ... :y ...}`, normalized 0–1
  against the original viewport. Framing clamps at viewport edges.
- `:transition` is seconds of eased motion, default 0.5. Zero changes the
  framing immediately. Motion uses smoothstep interpolation.
- The target holds indefinitely after the transition. Transitions may cross
  scene boundaries or continue during a narration checkpoint hold.
- If an instruction interrupts a transition, it starts from the current
  interpolated framing, not the previous target.
- List instructions in strict scene/time order; duplicate anchors are errors.
- Cuts remap retained anchors to edited time. An anchor inside a removed
  interval is an error: the agent must reposition it explicitly.
- Narration holds in earlier scenes shift later camera anchors automatically.
  Camera instructions do not extend the video; unfinished transitions stop
  at the end of the export.
- Timing is evaluated at 30 fps. Source footage and captions remain separate:
  captions never zoom with the browser.

**Migration:** remove the old `:zoom?` field and scene-level `:focus` maps.
They now produce actionable validation errors rather than silently changing
behavior. Put zoom/focus choices in `:camera` instead. Existing source
recordings remain reusable; captured legacy focus metadata is only a hint.

```sh
clojure -M:run render revised.edn work/capture-v1/capture.edn work/render-v2
```

Changing actions, adding/removing scenes, or reordering scenes requires a new
capture. The renderer rejects mismatched actions rather than suggesting they
ran. Capture manifests are trusted local data, not tamper-proof evidence.

## Agent-directed cuts

Add `:cuts` to an existing scene, then render again from the original capture:

```clojure
{:id "save"
 :narration "Save the project. The updated result appears."
 :cuts [{:start 1.2 :end 4.8}]
 ;; Keep the originally captured :actions unchanged.
 :actions [...]}
```

This removes footage from 1.2 seconds (inclusive) to 4.8 seconds (exclusive),
**relative to that scene's captured start**, not the complete recording or
edited output. The tool never chooses cuts automatically.

- Inspect source footage to choose boundaries. Absolute recording timestamps
  equal scene `:start` in `capture.edn` plus the cut's scene-relative time.
  Diagnostic action-event clocks are not exact encoded-frame timestamps.
- List cuts in ascending order without overlap. Boundaries snap forward to
  the 30 fps frame grid; each cut must remove at least one frame.
- Preserve the final scene frame: cuts ending at/beyond the scene end are
  rejected. This keeps the original checkpoint available for narration holds.
- Retained footage plays at normal speed, joined by hard cuts.
- Narration remains continuous; cuts affect browser footage, not speech.
  The planner recalculates scene lengths, subsequent scene starts, and
  sentence-caption timing from measured audio. It does not align spoken
  claims to particular browser actions; the agent must review that alignment.
- If narration outlasts shortened footage, the final checkpoint holds longer.
  **A cut may therefore not shorten the final video.** Shorten narration too
  when necessary. Without a checkpoint, excess narration still fails.
- Raw footage stays intact. Remove or revise cuts freely without recapture.

## Timing and presentation

- Default 1920×1200, 30 fps H.264/AAC capture and export; at most 62 scenes.
  Set `:viewport {:width 1920 :height 1080}` for 16:9, for example.
  Dimensions must be even integers: width 640–2560, height 400–1600.
  Changing an explicit viewport requires recapture, not upscaling old footage.
  Older projects without `:viewport` still render at their recorded dimensions.
- The browser viewport includes an extra 16px synchronization strip. Encoded
  color markers establish scene boundaries; the renderer crops the strip out.
  This avoids freezing stale frames because browser commands and encoded
  frames arrive at different times.
- Cursor movement and click pulses are temporary pointer-transparent overlays
  captured in footage. They do not alter business values. **Cursor styling is
  baked in**, unlike editable zooms, captions, and narration.
- Camera movement is off by default. The explicit `:camera` track controls
  transitions and persistent framing. Enabled camera transforms
  use a 4× full-chroma working image to reduce pixel-rounding jitter; output
  stays at the capture dimensions. This uses more rendering time and memory.
- Spoken text splits only at sentence boundaries, never at caption word counts.
  Each complete sentence has a measured Kokoro WAV, preserving within-sentence
  prosody. Its verbatim caption wraps visually into at most two lines. Shorten
  an oversized sentence rather than splitting its audio arbitrarily. This is
  sentence-level synchronization, not word-level forced alignment.
- Voice defaults: `af_heart`, generation speed 0.95, then pitch-preserving
  `atempo=1.25`.
- Longer narration holds the scene's final frame only if capture recorded a
  final checkpoint. Holds carry a small “Paused” label. Actions retain their
  original speed. Without a checkpoint, shorten narration or recapture.
- Captions render after zoom, scale proportionally with the viewport, and
  stay above player controls.
  There is no automatic UI collision avoidance; review framing and caption
  clearance. There is no automatic wait trimming or sensitive-data masking.
