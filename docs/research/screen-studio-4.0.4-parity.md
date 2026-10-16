# Screen Studio 4.0.4: feature parity for agents

## Bottom line

**Build a layered editor, not a larger recording script.** Agent Demo already
separates shooting from editing. The next architectural gap is that its cursor
and click effects are still baked into the source video. Screen Studio treats
them as editable presentation.

For browser demos, prioritize:

1. Clean footage plus synchronized cursor/click metadata.
2. Timed privacy masks and focus highlights.
3. Backgrounds, padding, corners, shadows, and reusable presets.
4. Better camera behavior, with readable, conservative defaults.
5. Output aspect ratios, export profiles, and caption sidecars.

Do not expand into webcam production, native desktop capture, mobile capture,
or hosted sharing merely to match a feature list. Those are separate product
scope decisions. Preserve the agent-first workflow and **cut waiting, not context**.

## Scope and confidence

Examined the supplied **Screen Studio 4.0.4-4926 Apple Silicon DMG** using
**REA 6.5.0**. Compared findings with the vendor's current public website and
selected guides, and with Agent Demo at commit `f74c65d`.

This is a **static feature/architecture investigation**, not a complete
reconstruction of Screen Studio's implementation or a runtime parity test.
The app, native helpers, and recovered JavaScript were not executed. No license
checks were bypassed, proprietary algorithms transplanted, or assets reused.

Evidence labels in this report:

- **P — Packaged:** metadata, literal UI/help text, or resource names exist in
  this exact build. This proves presence in the package, not that a code path
  is reachable, enabled, or works correctly.
- **W — Website:** the current vendor site documents the behavior. Its pages
  are not pinned to 4.0.4, so they cannot alone establish build-specific behavior.
- **A — Agent Demo:** behavior established by our code and existing tests.
- **Proposal:** an independent design recommendation, not recovered source.

### Artifact identity and trust

| Item | Finding |
| --- | --- |
| Bundle version | `4.0.4-4926`, matching the DMG filename |
| Bundle identifier | `com.timpler.screenstudio` |
| Package shape | Electron; main entry `dist-electron/index.js`; renderer chunks under `dist/` |
| Document types | `.screenstudio` project and `.screenstudiopreset` preset packages |
| DMG SHA-256 | `b2cdd6aa4bf957bdc5fe17bd95a1c3332b07ee5f2b7e00560ecd3644e517dc94` |
| ASAR SHA-256 | `7c5c012eedcfe831069d5e7c628cb0931ce11b401b51e75a984cd6a9ae2a9ced` |
| Apple code signature | `codesign --verify --deep --strict` returned 0 |
| ASAR integrity | REA reported **26 contradictions**; continued with `record-and-continue`, not ignored |

An accepted Apple code signature and an ASAR metadata mismatch are different
checks. Post-packaging signing/stripping is one possible explanation, not a
proven diagnosis. The affected bytes remain **observed-untrusted** in REA's
receipts. Analyzing an extracted directory reports its own new content identity;
it does not resolve the original archive contradictions.

## Feature comparison

“Missing” below means no implemented equivalent was found in the current Agent
Demo code, not that the feature must be added.

| Capability | Screen Studio evidence | Agent Demo today | Agent-oriented recommendation |
| --- | --- | --- | --- |
| Non-destructive source/edit separation | Raw-track export guide; project metadata filenames; timeline controls [W2, P1, P7] | **Present:** immutable capture plus version-2 source references, reorder/omit/reuse [A1] | Keep this foundation. Add more layers without rerunning actions. |
| Editable cursor size/style/smoothing | Cursor controls, idle hiding, shake removal, looping, rotation [P2, W1] | **Partial:** smooth pointer and pulse, but baked into video [A2] | Highest-leverage foundation: record events; draw cursor during render. |
| Automatic click-focused zoom | Auto/manual controls and click-based guide [P3, W3] | **Missing:** agent supplies camera explicitly | Offer opt-in suggestions from actions/targets. Do not silently zoom every click. |
| Manual persistent camera | Manual zoom controls [P3] | **Present:** scene-local instructions compile into a persistent track [A3] | Preserve explicit framing and “hold until changed.” |
| Loupe versus full-frame zoom | Both modes appear in packaged controls and public site [P3, W1] | **Missing:** full-frame crop/zoom only | Later, add a magnifier overlay that preserves surrounding context. |
| Early zoom lead-in | Packaged help describes animation starting before the zoom range [P3] | **Missing:** transition begins at its anchor | Optional pre-roll so a target is readable by the time an action happens. |
| Animation tuning and motion blur | Cursor/screen animation controls and mass/damping/stiffness terms [P4, W4] | **Partial:** smoothstep camera and supersampling; no motion blur | Add readable presets first. Exact proprietary spring/blur algorithms were not recovered. |
| Background, padding, rounded corners, shadow | Packaged controls and public presentation [P5, W1] | **Missing:** footage fills output | High-value branding with pure composition settings and presets. |
| Output aspect ratio and reframing | Editor aspect-ratio control; wide/square/vertical documentation [P6, W1] | **Missing as an edit feature:** changing capture viewport requires recapture | Separate source dimensions from output canvas; define fit/crop and safe areas. |
| Source trims/cuts and speed | Timeline slice trim/speed controls; typing-speed suggestion [P7, P6] | **Partial:** cuts/reuse; no arbitrary source in/out range or speed edits | Add explicit source ranges and rational speed mappings. Keep navigation intact by default. |
| Timed redaction and highlight masks | Separate mask types, duration warning, mask timeline controls [P8, W5] | **Missing:** private artifacts, but no video redaction | Priority feature. Begin with opaque rectangles and dim-outside highlights, not assumed automatic PII detection. |
| Mask tracking | Newer packaged masked-content-shift controls; helper executable present [P6, P15] | **Missing** | Unresolved: older public guide says masks do not follow scrolling. Verify before specifying parity. |
| Scripted voiceover | Packaged independent versus recording-attached modes [P9] | **Partial:** local Kokoro narration per edit scene, unaffected by video cuts, with checkpoint holds [A4] | Keep local speech. Add optional output-timeline versus source-attached audio later. |
| Captions from speech | Local/cloud transcription options, caption controls [P10, W1] | **Partial:** sentence-timed captions from supplied narration, not ASR [A4] | Add style/position controls and SRT/VTT before full transcription editing. |
| Transcript-based video editing | Distinct operations for removing video versus hiding caption words [P11] | **Missing** | Useful for imported speech, lower priority for agent-authored scripts. Preserve separate edit semantics. |
| Audio mix and enhancement | Microphone/system/background levels, background music, enhancement help [P12, W1] | **Missing:** generated narration only | Start with narration gain/normalization; optional music/ducking if requested. |
| Keyboard shortcut overlays | Packaged shortcut visibility, size, single-key controls [P13] | **Missing:** no keyboard-action track | Record safe shortcut metadata and render labels. Never collect password text or arbitrary typed content for this purpose. |
| Presets and preset sharing | Preset package type, apply/import/update controls [P14, W1] | **Missing as a first-class feature:** EDN can be copied manually | Versioned, allowlisted presentation-only presets; exclude auth, browser executables, commands, and private source paths. |
| Export choices | H.264/HEVC, frame-rate/quality/GIF controls [P16]; public 4K/60fps claims [W1] | **Partial:** fixed 30fps H.264/AAC output at capture dimensions [A5] | Add export profiles, target dimensions, GIF, caption sidecars; test actual source/output limits. |
| Frame-image export | Packaged timeline command [P7] | No public command; tests extract frames | Cheap agent-friendly preview/thumbnail capability. |
| Preview scrubbing and draft quality | Packaged playback-resolution and audio-scrubber controls [P7, P6] | **Partial:** render and inspect files; no dedicated preview command | Add low-resolution drafts, selected-frame previews, and validation reports instead of a GUI timeline. |
| Native display/window/area recording | Vendor capture docs and system/mic/camera controls [W1, P17] | **Out of scope:** browser-only Playwright | Defer unless product scope explicitly changes. |
| Webcam layouts, cutouts, face tracking | Overlay/split/fullscreen/cutout controls and helpers [P18, P15, W1] | **Out of scope / missing** | Do not prioritize for browser-agent reviewers. |
| iPhone/iPad capture and device frames | Public site [W1] | **Out of scope** | Separate adapter/project, not required for browser-demo parity. |
| Hosted links, comments, shared projects | Public site; export-to-link labels [W1, P19] | **Out of scope:** local MP4; documented manual GitHub upload | Keep local by default. Require explicit upload approval for any future integration. |
| Capture recovery | Public claim of periodic recording saves [W1] | Failed artifacts retained, but no verified crash-recovery contract | Add incremental durable manifests/checkpoints and kill/restart tests before claiming recovery. |

## What the package suggests about the design

### 1. Record facts; render presentation later

The public FAQ explicitly describes cursor-free screen footage plus synchronized
mouse data. The packaged main bundle names `mousemoves-0.json`,
`mouseclicks-0.json`, and `cursors.json`, alongside project/metadata files [P1].
Together these support the **separate cursor data** model. They do not establish
an exact on-disk schema or clock-conversion algorithm.

In Agent Demo, `capture/move!` injects the pointer into the page and records
monotonic event times. Scene boundaries come from encoded video markers, but
individual events are not all aligned to decoded frames [A2]. Before offering
post-capture cursor editing, build and test one consistent source-time mapping.

Existing footage with a baked pointer cannot gain a clean editable cursor
simply by drawing another one. Preserve legacy playback; require a new capture
for that new capability rather than pretending the old pointer can be removed.

### 2. Independent layers explain much of the apparent polish

Packaged labels distinguish zoom, camera-layout, shortcut, voiceover, and mask
tracks. Per-slice controls separately cover speed, audio volume, and cursor
visibility [P7, P6, P9]. This is evidence for independently editable features,
not proof of the full internal timeline implementation.

**Proposed Agent Demo boundaries:**

- Source assets: immutable video/audio and synchronized input events.
- Edit instances: source reference, source interval, cuts, speed, and order.
- Presentation: camera, cursor, masks, highlights, captions, and optional layouts.
- Appearance preset: canvas/background/padding/shadow/type styling.
- Export profile: dimensions, frame rate, codec, quality, and sidecars.

These are design proposals, **not supported new EDN keys**. Keep plans immutable
and pure; keep browser, speech, filesystem, and encoder effects at the edges.

### 3. Source time and output time must stay distinct

Packaged voiceover help explicitly distinguishes audio that follows recording
speed/cuts from audio that plays independently [P9]. Transcript controls also
distinguish cutting footage from hiding text in captions [P11].

Agent Demo currently keeps generated narration continuous within an edit scene
and remaps camera anchors through cuts and earlier holds. Extend that deliberate
model instead of giving every future layer an ambiguous `time` field. Define
source-relative versus output-relative timing and test each transformation.

### 4. Defaults matter more than exposing every slider

Screen Studio offers animation styles, presets, automatic zoom, and quick export
controls [P3, P4, P14, P19]. For agents, the equivalent is a small declarative
surface with useful defaults, preview feedback, and clear errors—not a copied GUI.

Keep our existing choices: zoom is opt-in; narration must match observed results;
long completion waits are allowed; cuts remove waiting, not navigation context.
A parity project should not reintroduce the confusing camera movement we removed.

## Suggested implementation order and acceptance tests

### Phase 1 — Editable layers and privacy

**Build:** clean capture + cursor sidecar, render-time cursor/pulses, explicit
source/output clock mappings, timed opaque masks, focus highlights.

**Acceptance:** change cursor size/style without recapture; no double cursor;
click timing stays aligned after a cut and reordered/repeated scenes; masks cover
sensitive regions through camera motion and every affected output frame. Raw
footage and manifests remain private even when the final export is masked.

### Phase 2 — Presentation and output framing

**Build:** canvas/background/padding/corners/shadow, presentation presets,
wide/square/vertical output fit/crop, caption positioning and safe areas.

**Acceptance:** three aspect ratios from one capture with unchanged source hash;
text remains readable; targets/captions do not collide; presets cannot contain
credentials or executable configuration.

### Phase 3 — Camera, timing, and audio

**Build:** camera style presets and optional lead-in; action-target-assisted zoom
suggestions; source in/out ranges and speed edits; narration gain and optional
source-attached audio. Consider loupe and motion blur after readability tests.

**Acceptance:** no unexplained navigation skips; camera settles before important
text must be read; cuts/speed/holds preserve intended event and audio alignment;
the default camera remains stationary unless the edit requests movement.

### Phase 4 — Agent iteration and delivery

**Build:** draft/frame previews, render progress/cancellation, reusable export
profiles, SRT/VTT, GIF, incremental durable capture manifests.

**Acceptance:** preview and final export use the same edit semantics; cancellation
preserves reusable source; crash recovery has explicit tested guarantees; media
outputs decode cleanly and sidecar timestamps match the edited video.

Native desktop/mobile adapters, webcam production, transcript editing, and cloud
collaboration should each require a separate scope decision.

## Remaining questions before claiming close parity

1. How do automatic zooms choose duration, cluster clicks, and resolve overlaps?
   Literal UI text and a guide do not recover the algorithm.
2. What exact spring, loupe, motion-blur, and super-resolution behavior ships in
   this build? Names and controls are not numerical or visual equivalence tests.
3. Do masks in this build track scrolling reliably? Packaged shift-detection
   controls and the older guide disagree about the apparent capability.
4. Which UI controls are active, experimental, or unused? Some packaged text is
   explicitly for QA/debug workflows and should not become a product requirement.
5. What are the exact source/project schemas and cross-version migration rules?
   We inspected bundle metadata and filename literals, not a newly saved project.
6. How does export perform, and what does it produce at each codec/frame rate?
   No licensed app export or visual comparison was performed.
7. Which features merit parity for agents? Browser-safe automation and local
   generated narration are differentiators, not things to replace with human UX.

For a deeper pass, use a legitimately activated app with a synthetic fixture:
record a short known sequence, save its project, vary one setting at a time,
and compare source/project changes and rendered frames. Do not use private
browser profiles or bypass activation. No further installation or app execution
was performed as part of this pass.

## Evidence index

Paths below are relative to the REA-extracted ASAR. Offsets are zero-based
**character offsets** in the original UTF-8-decoded file, not machine addresses.
They point to literal strings or identifiers, not necessarily executable callers.
The local receipt records full file hashes. Escape sequences were decoded as
text; no obfuscator bootstrap or target code was evaluated.

| Ref | Packaged anchor |
| --- | --- |
| P1 | `dist-electron/index.js`: `project.json` at 2309765; `mousemoves-0.json` at 2775262; `mouseclicks-0.json` at 2295458; `cursors.json` at 2802967; recording-marker filename at 2839045 |
| P2 | `dist/aUi7XlwL.js`: shake-removal control at 26546; idle hiding at 27894; click-sound volume at 28159; size at 29607; style at 30035 |
| P3 | `dist/D7Bm9Tba.js`: zoom style at 1800; lead-in explanation at 2510; cursor-visibility help at 2860. `dist/DFpIYHN22.js`: loupe/classic labels at 8718/8734 |
| P4 | `dist/C9ncRpnp2.js`: motion-blur help at 2135; mass/damping/stiffness identifiers at 1963/3315/3639 |
| P5 | `dist/LpFI1TRx.js`: shadow blur at 2207; distance at 2606; color at 2665; background image at 3744; angle at 7306 |
| P6 | `dist/D8qyO3i92.js`: output aspect ratio at 285236; typing-speed help at 287130; cursor visibility at 297107; mask-shift detection at 273667; preset reuse at 313851 |
| P7 | `dist/BtdkWdRa.js`: slice splitting at 21143; trim reset at 21692; speed at 22088; per-source audio levels at 22203/22725; frame export at 24178 |
| P8 | `dist/CxOVrIGj.js`: highlight/sensitive-data types at 2413/2599. `dist/bVpCYoxS.js`: full-duration coverage warning at 1912 |
| P9 | `dist/Dhy1dLPO.js`: independent/attached help at 692/997; `attachMode` at 116 |
| P10 | `dist/_KfiRrzA.js`: local Apple/Whisper help at 3458/3801; caption line behavior at 5263; transcript export at 2058 |
| P11 | `dist/Chuojjj4.js`: video deletion versus caption-only deletion help at 8611/9262 |
| P12 | `dist/DP3F8eej.js`: background audio at 4351; noise/echo help at 4381; voice-level help at 5929 |
| P13 | `dist/C3z7r8IY.js`: shortcut-label help at 1183; single-key option at 1501 |
| P14 | `dist/CUWd7pwI2.js`: apply-preset operation label at 10018; default-preset label at 10618; preset document type in bundle plist |
| P15 | Packaged helper names: `bin/dynamic-mask-tracker`, `bin/face-detector`, `bin/segment-video`, `bin/detect-silence`, `bin/transcribe-audio`, `bin/whisper-darwin-arm64`; names alone do not prove implementation details |
| P16 | `dist/BwBPUc-h2.js`: HEVC/H.264 labels at 1578/1626; FPS at 1657; codec at 2085; GIF loop option at 3099 |
| P17 | `dist/C46aqj60.js`: selected-app system audio at 2276; all-app audio at 3241; webcam at 4750; microphone at 5974 |
| P18 | `dist/C9Uf1WBq2.js`: split screen at 22723; cutout at 23266; overlay at 23723; fullscreen camera at 23981 |
| P19 | `dist/B5hBhD1U.js`: clipboard export at 1559; shareable-link export at 2382; file export at 3523 |

Public sources:

- **W1:** [Screen Studio product page](https://screen.studio/), captured during this investigation; current, not version-pinned.
- **W2:** [Extracting raw recording files](https://www.screen.studio/guide/extracting-raw-recording-files).
- **W3:** [Auto zoom](https://www.screen.studio/guide/auto-zoom).
- **W4:** [Animations](https://www.screen.studio/guide/animations).
- **W5:** [Masks and highlights](https://www.screen.studio/guide/adding-a-mask-and-highlight).

Agent Demo references at `f74c65d`:

- **A1:** `core.clj`, `recorded-scenes`/`plan`; version-2 examples and edit regression tests.
- **A2:** `capture.clj`, `move!`/`action!`/`capture!`; `resources/pointer.js`.
- **A3:** `camera.clj`, `compile-track`/`filter-expression`.
- **A4:** `speech.clj`, `generate!`/`speech!`; `core.clj`, `cues`/`plan`; `render.clj`, `caption!`.
- **A5:** `render.clj`, `render-segment!`/`render!`.

## Local research artifacts and reproducibility

Private research files live under ignored `work/screen-studio-research/`:

- `research-receipt.json`: identities, file hashes, versions, and limitations.
- `dmg-evidence-recorded.json`: REA package inspection and integrity contradictions.
- `asar-extraction.json`: REA extraction manifest for 4,838 files.
- `application-evidence.json`: first four-file static analysis; one large renderer
  file hit a maximum-call-stack constraint.
- `feature-module-evidence.json`: focused ten-file analysis, no parse failures;
  selected-file scope and semantic-node limits still make coverage partial.
- `feature-labels.json`: supplementary text-only literal inventory, not a REA
  proof of reachability or a complete JavaScript parser.
- `signature-verification.txt`, downloaded public pages, and extracted package.

REA evidence IDs:

- Initial application pass: `ev_ef2dbe34e8a282f7b9d3012ec7c2b9749afcbe520ef1d88106f68929555e86dc`.
- Focused feature pass: `ev_796c2ab5c858de6702e44ef792f7fad12ad77c6f9785963035808cf5b404cdfb`.

Reproduction outline (no global install or MCP configuration changes required):

```sh
bunx rea-agents@6.5.0 inspect-artifact /path/to/supplied.dmg \
  --integrity-policy record-and-continue --json
# Mount the supplied DMG read-only, then point REA at its app.asar:
bunx rea-agents@6.5.0 extract-artifact /path/to/app.asar \
  --integrity-policy record-and-continue --json
# Use the output_root returned by extraction, not an assumed directory:
bunx rea-agents@6.5.0 analyze-javascript-application /path/to/extracted \
  --source-path dist/Dhy1dLPO.js --source-path dist/D7Bm9Tba.js --json
```

Record-and-continue is specific to preserving the observed contradictions in
this investigation, not a recommendation to trust unexpected package changes.
REA's DMG inspection worked; its DMG extraction command returned unsupported,
so extraction used the ASAR from the read-only mount. The existing npm launcher
was broken; Bun's package runner provided the pinned CLI without changing npm.
Raw extracted files and full evidence are deliberately not committed or published.
Only this independently written report belongs in repository documentation.
