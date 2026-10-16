# Agent guide

Use the supplied CLI to produce a demo; do not write new recording helpers.
Read [the project format](project-format.md) before authoring EDN. All shell
commands and example paths below are relative to the repository root, not
this documentation directory.

[Back to README](../README.md)

## Requirements

- Clojure CLI and JDK 17+.
- Installed Google Chrome, or an explicit Chromium executable.
- FFmpeg with H.264/AAC encoding and FFprobe on PATH.
- Playwright's recording helper (one explicit download):
  `clojure -M -m com.microsoft.playwright.CLI install ffmpeg`
- A **preinstalled**, OpenAI-compatible Kokoro service exposing
  `GET /health` (`{"status":"ok"}`) and `POST /v1/audio/speech`.

Clojure resolves pinned Maven dependencies on first use. Capture disables
Playwright's automatic browser downloads and defaults to installed Chrome.
Nothing installs or downloads Kokoro. No cloud speech fallback exists.

## Setup checklist

1. **Find or clone the checkout.** Reuse an existing clone; otherwise choose
   an available directory and run:

   ```sh
   git clone https://github.com/juniusfree/agent-demo.git
   cd agent-demo
   ```

   Run all following commands from the checkout root.

2. **Check prerequisites before changing the machine.** Confirm the Clojure
   CLI, JDK 17+, Chrome (or a configured Chromium executable), FFmpeg, and
   FFprobe. Useful checks:

   ```sh
   clojure -Sdescribe
   java -version
   ffmpeg -version
   ffprobe -version
   ```

   Ask before installing missing dependencies. Do not assume a package manager
   or operating system. Capture defaults to the installed Chrome channel;
   configure `:browser :executable` when using another Chromium installation.

3. **Resolve dependencies and install the recording helper.** These commands
   download Maven dependencies and Playwright’s FFmpeg helper. Run them after
   confirming that downloads are permitted:

   ```sh
   clojure -P
   clojure -M -m com.microsoft.playwright.CLI install ffmpeg
   clojure -M:test
   clojure -M:test-zoom
   ```

   Do not run a blanket Playwright browser install: this tool uses the
   installed browser by default.

4. **Configure local speech.** Check the user's preinstalled Kokoro service:

   ```sh
   curl --fail --silent --show-error http://127.0.0.1:8880/health
   ```

   The tool expects `{"status":"ok"}`. If the service is unavailable, ask for
   its actual foreground launch command and configure `:kokoro :command` in
   a local project EDN. The tool will start it and wait for readiness. Do not
   invent a launcher, install models, use cloud speech, or stop an existing
   service. Health alone does not prove speech generation works; rendering
   the fixture verifies that next.

5. **Capture and render the fixture.** Follow [Run the fixture](#run-the-fixture)
   below. If you need a custom speech configuration, copy `examples/fixture.edn`
   into `work/` and use that copy for both capture and render. Keep the fixture
   server running in a separate terminal or a managed background process.
   Use fresh output directories for retries; retain failed artifacts.

6. **Review and hand off.** Check the exported video's framing, captions,
   audio, and demonstrated result. If you cannot listen or watch playback,
   state that limitation. Report the absolute checkout and MP4 paths, dependency
   configuration, tests run, and outstanding checks. Stop only fixture/service
   processes you started. Keep private auth state and raw recordings out of Git.

For a real app, obtain its URL, the feature to demonstrate, and authorized
throwaway account/data. Follow the [agent contract](#agent-contract). Camera
movement is opt-in through `:camera`; cuts are explicit agent-authored edits.
Neither requires new capture helpers.

## Run the fixture

From this directory, start the fixture in one terminal:

```sh
clojure -M:run fixture
```

In another:

```sh
clojure -M:run validate examples/fixture.edn
clojure -M:run capture examples/fixture.edn work/capture-v1
clojure -M:run render examples/fixture.edn work/capture-v1/capture.edn work/render-v1
open work/render-v1/demo.mp4 # macOS; use your video player on other systems
```

Every capture/render requires a **new output directory**. Failed runs retain
their partial artifacts for diagnosis; use a new directory when retrying.

## Agent contract

1. Use an authorized, disposable application fixture.
2. Write an EDN project with a narration and action sequence per scene.
3. Start with a `:goto` action. Initial navigation is excluded from exported
   content, so the first scene opens on the app rather than browser setup.
4. Use explicit waits for visible completion, not guessed save delays.
5. End a scene with `:checkpoint` only when its final frame is safe to hold.
6. Capture once. Review the real results before narrating success.
7. Edit narration, cuts, and the camera track, then render again from the same capture.
8. Inspect final frames and listen before sharing. The tool does not judge
   whether narration is true.

This version runs a **batch of agent-authored actions**. The agent chooses
everything; the adapter does not plan or explore. Interactive per-action CLI
sessions, MCP, popups, iframe targeting, and arbitrary JavaScript actions are
not implemented.

See [Project format](project-format.md) for EDN, actions, persistent camera
instructions, cuts, and presentation timing.

## Troubleshooting

| Symptom | Next step |
| --- | --- |
| Browser or recording helper missing | Verify Chrome or the configured executable; install only the documented recording helper after download approval. |
| Kokoro unavailable | Check the loopback endpoint or request the actual foreground launcher. Do not invent a command or substitute cloud speech. |
| Selector fails or times out | Inspect the real UI and use a unique selector with an explicit completion wait. Recapture after changing actions. |
| Narration exceeds footage | Shorten the narration, or recapture with a final stable checkpoint. |
| Caption exceeds two lines | Rewrite the sentence more concisely; do not split speech arbitrarily mid-sentence. |
| Camera anchor falls inside a cut | Move the anchor to retained footage in the original scene timeline. |
| Output directory already exists | Choose a fresh directory. Preserve failed artifacts for diagnosis. |
| Old zoom configuration rejected | Replace `:zoom?` and scene-level `:focus` with the explicit camera track described in the format guide. |

## Kokoro lifecycle

The tool checks the configured loopback origin before generating speech:

1. Reuse a healthy existing service without taking ownership.
2. Otherwise execute the configured command directly (no shell interpolation).
3. Wait for health, bounded by the startup timeout.
4. Generate speech; on success or failure, terminate only the owned process
   and its descendants.

Startup logs live in the render directory's `kokoro.log`. Missing
configuration, timeout, an exited process, or a failed speech request produces
an error. Source capture remains available. Concurrent renders should share an
externally managed Kokoro service; owned service startup is not coordinated
across CLI processes.

## Files and privacy

Capture directory:
- `project.edn`: original project configuration.
- `capture.edn`: absolute source path, encoded scene bounds, actions and events.
- `raw/*.webm`: original private recording, including synchronization/setup.

Render directory:
- `demo.mp4`: shareable only after review.
- `project.edn`, `timeline.edn`: reproducible presentation decisions and timings.
- Per-cue WAVs, caption PNGs, intermediate scene videos.
- `verification.edn`: render metadata and outstanding review checks.

Events use a monotonic action clock for diagnostics; only encoded marker
bounds drive video cuts. Capture manifests use absolute paths: update
`:source` if deliberately relocating footage.

Keep raw footage, authentication state, logs, and configuration private.
The tool does not upload anything, redact recordings, or package artifacts
for sharing. EDN cannot execute code through the reader, but a configured
Kokoro command **is executable configuration**: use trusted project files.

## Publishing a demo on GitHub

For playback inside a README, upload an **attachment**, not a release asset.
GitHub release download URLs produce downloads; ordinary HTML video tags
are sanitized. The attachment URL must stand alone in its own paragraph.

After obtaining permission to publish the reviewed demo, the current GitHub
CLI supports:

```sh
gh issue create --title "README demo attachment" \
  --body "Reviewed demo video for the README." --attach work/render-v1/demo.mp4
gh issue view ISSUE_NUMBER --json body --jq .body
```

Copy the generated `https://github.com/user-attachments/assets/...` URL into
the README as a bare URL, not a Markdown image or download link. Close the
hosting issue when done; keep the attachment. Check the rendered README for
a video player and mention that viewers must enable sound. Do not copy the
temporary signed media URL from the rendered HTML.

This publishes both an issue and its attachment; never upload private raw
recordings. Check `gh issue create --help` for `--attach` support before use.
See [GitHub's attachment instructions](https://docs.github.com/en/github-cli/github-cli/attaching-files-with-github-cli).

## Functional architecture

| Namespace | Responsibility |
| --- | --- |
| `core` | Pure validation, cue segmentation, cut and narration timeline planning |
| `camera` | Pure camera validation, anchor remapping, persistent state, FFmpeg expressions |
| `capture` | Playwright browser adapter and encoded boundary extraction |
| `speech` | Local HTTP and owned-process lifecycle |
| `render` | Caption rasterization and FFmpeg composition |
| `io` | EDN files and external process boundary |
| `main` | CLI and local fixture server |

The rendering plan is ordinary immutable EDN. No browser handles, subprocesses,
or mutable agent session state enter the planner. Java AWT rasterizes caption
images so the renderer does not depend on FFmpeg's optional libass/drawtext
filters. Java Playwright is the browser bridge; the only JavaScript is the
small UI overlay and browser evaluation needed by the adapter.

## Tests

```sh
clojure -M:test
clojure -M:test-zoom
clojure -M:test-cuts work/cut-check-v1
clojure -M:smoke work/acceptance-v1
```

The cut regression renders a synthetic action/wait/result recording and
verifies that the action and result survive while the wait and its duration
are removed. It needs FFmpeg but no browser or speech service.

Unit tests cover invalid projects/captures, agent-directed cuts, pure timing, explicit checkpoints,
revisions, marker decoding, loopback-only speech, and service ownership/failure
cleanup. No browser or speech service is needed for unit tests.

The smoke test needs the requirements above. It starts an ephemeral local
fixture, tests a synthetic stale-frame regression, captures real interactions,
renders twice with different narration/zoom, decodes both outputs, checks
audio/video streams and durations, verifies the source hash stays unchanged,
and extracts final frames for review. It never restarts an existing service.

See [verification.md](../verification.md) for the implementation run's evidence
and remaining limitations.
