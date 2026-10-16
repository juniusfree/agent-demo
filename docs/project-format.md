# Project format

Agent Demo uses text as an editable video project. **Capture produces footage;
an edit references that footage.** Editing never reruns browser actions.

Start with the [agent guide](agent-guide.md). Commands and paths are relative
to the checkout root. [Back to README](../README.md)

## Version 2: separate shooting from editing

Use two files so presentation changes cannot change the shooting recipe.
Both may use the same `:version 2` schema. A combined file with both
`:capture` and `:scenes` also works, but separate files are easier to review.

### Shooting recipe: `work/shoot.local.edn`

```clojure
{:version 2
 :viewport {:width 1920 :height 1200}
 :browser {:timeout-ms 60000
           ;; Optional private session from the auth command:
           ;; :storage-state ".auth/demo-v1/storage-state.json"
           }
 :capture
 {:scenes
  [{:id "name"
    :actions [{:op :goto :url "http://127.0.0.1:8799"}
              {:op :fill :selector "#name" :text "Reviewer demo"}
              {:op :checkpoint}]}
   {:id "save"
    :actions [{:op :click :selector "#save"}
              {:op :wait-text :selector "#result" :text "Saved"}
              {:op :checkpoint}]}]}}
```

Capture scenes contain unique IDs, browser actions, and optionally a
`:focus-selector` for advisory target geometry. They do not contain narration,
camera instructions, or cuts. Capture runs them once, in recipe order.
Authentication setup reads this recipe too; it uses only the initial URL and
browser executable, not the action sequence.

### Edit: `work/edit.local.edn`

```clojure
{:version 2
 :kokoro {:endpoint "http://127.0.0.1:8880" :voice "af_heart"}
 :scenes
 [{:id "opening"
   :source "save"
   :narration "Here is the saved project."
   :camera [{:time 0 :zoom 1 :transition 0}]}
  {:id "explanation"
   :source "name"
   :narration "Give the project a clear name."
   :camera [{:time 0 :zoom 1.4
             :focus {:x 0.3 :y 0.4} :transition 0.6}]}
  {:id "reprise"
   :source "save"
   :narration "The confirmation shows the project is saved."
   :camera [{:time 0 :zoom 1 :transition 0.5}]}]}
```

- `:id` uniquely identifies this **edit instance**, including its audio and
  intermediate files. Use letters, digits, hyphens, or underscores.
- `:source` names a **recorded scene ID** in the capture manifest supplied to
  `render`. It is not a file path or an index into the shooting recipe.
- Vector order determines playback. Reorder, omit, or repeat source scenes.
  Each repeated instance has its own ID, narration, cuts, and camera settings.
- Rendering uses the recorded manifest, not the current shooting recipe.
  Changing a recipe does not change footage already recorded. An optional
  `:capture` section in a combined file is ignored during rendering.
- One edit uses one capture manifest. Importing unrelated recordings, arbitrary
  source in/out ranges, silent scenes, and multiple video tracks are not yet
  supported. Use cuts to remove intervals from a recorded scene.
- Narration is required per edit scene. Captions use the same text; independent
  caption text or word-to-action synchronization is not supported.
- Camera is optional and defaults to full view until the first instruction.
  It persists across edit instances, including repetitions. Omit it to keep
  current framing, or set `:zoom 1` to return to full view.

### Commands

```sh
clojure -M:run validate work/shoot.local.edn
clojure -M:run validate work/edit.local.edn
# If sign-in is needed, follow the interactive authentication guide:
clojure -M:run auth work/shoot.local.edn .auth/demo-v1
# Add the resulting storage-state path to the shooting recipe before capture.
clojure -M:run capture work/shoot.local.edn work/capture-v1
clojure -M:run render work/edit.local.edn work/capture-v1/capture.edn work/render-v1
# Edit narration/order/cuts/camera, then reuse exactly the same capture:
clojure -M:run render work/edit.local.edn work/capture-v1/capture.edn work/render-v2
```

Every output directory must be new. `examples/fixture-shoot.edn` and
`examples/fixture-edit.edn` form a runnable pair; the edit opens with the saved
result, explains naming, then repeats the result while omitting access footage.
Reordering does not undo any application state changes made during capture.
Review the story so the new order and narration do not misrepresent events.

## Browser actions

| Operation | Fields | Behavior |
| --- | --- | --- |
| `:goto` | `:url` | Navigate to HTTP(S), without embedded credentials |
| `:click` | `:selector` | Smooth cursor movement, click pulse, real click |
| `:fill` | `:selector :text` | Move cursor and fill; password inputs are blocked |
| `:hover` | `:selector` | Move cursor and hover |
| `:wait-visible` | `:selector` | Wait for visibility |
| `:wait-text` | `:selector :text` | Wait for a unique CSS match containing text |
| `:checkpoint` | none | Mark the final screen as safe to hold |

The first capture scene must start with `:goto`. Use unique CSS selectors.
Browser actions, completion waits, and navigation default to 15 seconds.
Set `:browser {:timeout-ms 60000}` in the shooting recipe for slow pages.
The value must be an integer from 1 to 600000 milliseconds (10 minutes);
zero cannot disable timeouts. This works in versions 1 and 2. It also applies
to auth setup navigation, but does not time-limit the user's manual sign-in.
It is a maximum wait, not a sleep: operations finish as soon as they succeed.
Longer capture waits do not alter playback speed; remove measured loading
intervals with edit-scene cuts afterward.

Use a disposable, authorized fixture; isolation is not authorization to change
production data.

Installed Chrome is the default. Set `:browser :executable` to use another
trusted Chromium installation. Sign in through the [auth workflow](agent-guide.md#authentication-setup),
not recorded actions. Keep storage state outside Git.

## Scene-local camera

```clojure
:camera [{:time 0.2 :zoom 1.4 :focus {:x 0.3 :y 0.4} :transition 0.6}
         {:time 3.0 :zoom 1 :transition 0.5}]
```

- Times are seconds relative to the **original recorded source scene**, not
  edited playback. Measure first; the example requires at least three seconds.
- Zoom ranges from 1 to 2.5. Above 1, `:focus` requires normalized x/y coordinates
  in the original viewport. Coordinates range from 0 to 1.
- Transitions default to 0.5 seconds, with smoothstep easing. Zero is immediate.
- Instructions must be strictly ordered within a scene. A source-end anchor
  is allowed, but anchors inside removed intervals are rejected.
- Cuts and earlier narration holds remap anchors to output time. Camera
  transitions can span scene boundaries and holds; an interrupted transition
  starts from its current interpolated state. They do not extend the export.
- Framing remains until the next instruction, even in another edit scene.
  Local camera instructions flatten into one continuous output track.
- Anchors that coincide after editing are rejected; move or remove one.
- Captions do not zoom. Rendering retains full-chroma, 4x supersampling to
  avoid camera rounding jitter. This costs more processing time.

## Cutting loading intervals

After inspecting the captured footage, add cuts to an **edit scene**:

```clojure
{:id "short-save"
 :source "save"
 :narration "Save the project."
 :cuts [{:start 0.5 :end 2.5}]}
```

These example times require footage longer than 2.5 seconds. Cuts remove
`start` inclusive to `end` exclusive in original source-scene seconds.
Intervals must be ordered, nonoverlapping, remove at least one frame, and
preserve the final source frame. Boundaries snap forward to the 30fps grid.
Each reused instance can choose different cuts without modifying the source.

Cuts remove video, not speech. Long narration may replace removed loading
with a final-frame hold rather than shortening the output. Shorten narration
as well if you need a shorter video. There is no automatic loading detection.

## Timing, media, and global settings

- Capture defaults to 1920×1200. Viewport width must be an even integer between
  640 and 2560; height between 400 and 1600. Capture supports at most 62 scenes.
- Omit viewport from the edit to use recorded dimensions. An explicitly
  different viewport is rejected: changing source resolution needs recapture.
- The renderer exports 30fps H.264/AAC. Encoded markers define capture scene
  boundaries; the extra 16px marker strip is cropped from output.
- Pointer/click overlays are baked into footage, not separately editable.
- Each edit scene's narration starts with that scene. Kokoro synthesizes full
  sentences; captions follow measured sentence audio. Captions wrap to at most
  two lines. Shorten long sentences rather than splitting speech arbitrarily.
- Footage plays at normal speed. When narration needs extra time, the final
  frame holds, but only if the source scene ended with a checkpoint. Otherwise
  rendering fails. There is no pause label. Longer footage continues after speech.
- Kokoro defaults to loopback port 8880 and voice `af_heart`. Speech uses speed
  0.95 followed by pitch-preserving `atempo=1.25`. Rendering reuses a healthy
  service or runs an explicitly configured, preinstalled `:kokoro :command`.
  Do not configure a daemonizing launcher. There is no cloud fallback.
- Files, manifests, selectors, and narration can contain private data. Nothing
  is automatically redacted. Keep source files private and review the final MP4.

## Version 1 compatibility and migration

Existing `:version 1` projects, capture manifests, and examples still work.
Version 1 combines narration and actions in each scene and uses a top-level
`:camera` track with `:at {:scene "id" :time seconds}`. It retains its strict
scene-order and action-match checks; use version 2 for reordering/reuse.

To migrate without reshooting:

1. Create a version-2 edit with scene IDs, narration, and cuts from the old
   project. Add `:source` equal to each original recorded scene ID.
2. Move camera instructions into their matching edit scenes, replacing
   `:at {:scene "id" :time t}` with `:time t`. Preserve instruction order.
3. Remove actions and focus selectors from the edit. Render it against the
   **existing** `capture.edn`; capture manifests remain version 1.
4. For future shoots, put original IDs, actions, and optional focus selectors
   into a separate version-2 `:capture {:scenes [...]}` recipe. Move browser
   and viewport settings there; keep Kokoro settings in the edit.

Do not simply change the version number. Mixed layouts are rejected. Earlier
`:zoom?` and scene `:focus` schemas are still unsupported.
