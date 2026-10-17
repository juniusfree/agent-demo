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
   below. If you need a custom speech configuration, copy `examples/fixture-edit.edn`
   into `work/` and use that copy for render. Keep the fixture
   server running in a separate terminal or a managed background process.
   Use fresh output directories for retries; retain failed artifacts.

6. **Review and hand off.** Check the exported video's framing, captions,
   audio, and demonstrated result. If you cannot listen or watch playback,
   state that limitation. Report the absolute checkout and MP4 paths, dependency
   configuration, tests run, and outstanding checks. Stop only fixture/service
   processes you started. Keep private auth state and raw recordings out of Git.

For a real app, obtain its URL, the feature to demonstrate, and authorized
throwaway account/data. If sign-in is required, use [Authentication setup](#authentication-setup)
before capture; do not attach to the user's daily Chrome profile. Follow the [agent contract](#agent-contract). Camera
movement is opt-in through scene-local `:camera`; cuts are explicit agent-authored edits.
Neither requires new capture helpers.

## Run the fixture

From this directory, start the fixture in one terminal:

```sh
clojure -M:run fixture
```

In another:

```sh
clojure -M:run validate examples/fixture-shoot.edn
clojure -M:run validate examples/fixture-edit.edn
clojure -M:run capture examples/fixture-shoot.edn work/capture-v1
clojure -M:run render examples/fixture-edit.edn work/capture-v1/capture.edn work/render-v1
open work/render-v1/demo.mp4 # macOS; use your video player on other systems
```

The version-2 shooting recipe contains browser actions; the separate edit
contains playback order, narration, cuts, and scene-local camera instructions.
The sample edit reorders and reuses captured scenes. Change the edit and render
again from the same capture. Browser actions run only when you call `capture`.

Every capture/render requires a **new output directory**. Failed runs retain
their partial artifacts for diagnosis; use a new directory when retrying.

## Longer launch walkthrough

The backward-compatible version-1 `examples/launch.edn` tells a roughly 55-second story: name a project, choose
team access, add three tasks, complete one, filter remaining work, save, and
reload. The fixture stores data in browser-local storage only; its Team
setting illustrates intent, not real multi-user authorization.

With the fixture server running, use fresh output directories:

```sh
clojure -M:run validate examples/launch.edn
clojure -M:run capture examples/launch.edn work/launch-capture-v1
clojure -M:run render examples/launch.edn work/launch-capture-v1/capture.edn work/launch-render-v1
clojure -M:test-fixture
```

The browser test covers responsive layouts, previews, access selection, task
completion/filtering, persistence after reload, storage failure, and corrupt
saved data. It needs installed Chrome but does not need Kokoro. Captures use
an isolated context, so each demo starts with an empty browser store.

## Authentication setup

Use Agent Demo's `auth` command instead of remote debugging on a signed-in
personal browser. It opens a separate, visible Chrome instance with a fresh
context. It does not record video, trace, take screenshots, run scene actions,
or reuse an existing `:storage-state`. Use a dedicated demo account.

1. Put the real app's URL in the first `:goto` of your local shooting recipe
   (`:capture :scenes` in version 2; `:scenes` in version 1).
2. Run this command in an **interactive terminal** the user can access:

   ```sh
   clojure -M:run auth work/demo.local.edn .auth/demo-v1
   ```

3. The user signs in manually in the new browser, including SSO/MFA, then
   types `verify` in the terminal. Do not put passwords or MFA codes into
   EDN, chat, or an agent tool call.
4. Agent Demo closes that context and opens the original app URL in a fresh
   context with the saved state held in memory. The user checks that the
   intended account is still signed in **without signing in again**, then
   types `save`. If the login page returns or the wrong account appears,
   cancel instead.
5. Only then does Agent Demo write `.auth/demo-v1/storage-state.json`.
   Add its path to the original local project:

   ```clojure
   :browser {:storage-state ".auth/demo-v1/storage-state.json"}
   ```

6. Run capture with that project. Keep the exact app origin, including
   hostname and port; `localhost` and `127.0.0.1` are different origins.
   Review the result before sharing.

The CLI also honors `:browser :executable` and `:browser :timeout-ms` during
setup navigation. The timeout does not limit the user's manual sign-in. All paths are
relative to the command's working directory unless absolute. Each setup
requires a new output directory; expired sessions require setup again with
a new directory and an updated project path. Nothing overwrites old sessions.

**For agents:** keep the interactive command running while the user signs in.
Explain the two confirmation steps. Do not send `verify` or `save` without
the user's confirmation. EOF or any other response cancels; a noninteractive
shell with closed stdin cannot complete setup. Never ask the user to enable
remote debugging on their personal profile.

The state includes cookies, local storage, and IndexedDB, potentially for an
identity provider as well as the app. Treat it as a credential. New directories
use 0700 and the state file uses 0600 on POSIX; check inherited ACLs elsewhere.
Both `.auth/` and `storage-state*.json` are ignored by Git.

Session storage and device-bound credentials are not transferred. The fresh
context check exposes many such failures, but does not guarantee that a
session will remain valid in a later headless capture. If reuse fails, stop
and use an app-supported demo/dev login rather than copying a personal browser
profile or putting login actions into the recording. Setup is not read-only:
signing in can create server sessions and audit events.

## Agent contract

1. Use an authorized, disposable application fixture.
2. Write a version-2 shooting recipe with IDs and actions in `:capture :scenes`.
   Write a separate edit with `:scenes`; each edit instance has a unique `:id`,
   a recorded `:source` scene ID, narration, and optional cuts/camera/cursor settings.
3. Start the shooting recipe with a `:goto` action. Initial navigation is
   excluded from exported content, so footage opens on the app rather than setup.
4. Use explicit waits for visible completion, not guessed save delays.
5. End a captured scene with `:checkpoint` only when its final frame is safe to hold.
6. Capture once. Review the real results before narrating success.
7. **Cut waiting, not context.** Preserve the viewer's sense of where they are
   and how they got there. Show navigation clicks, page changes, and meaningful
   state transitions. Cut only idle waiting after the action is clear, and keep
   the resulting state visible. Do not skip steps or reorder footage by default;
   do so only when requested. Judge cuts by the context they remove, not length:
   a long loading wait may be safe to cut, while a brief navigation step may not.
8. Edit narration, cuts, camera, or cursor settings, then render again from the
   same capture. Do not run `capture` for presentation-only changes. When a
   requested edit reuses a source scene, give each instance a distinct edit ID.
9. Watch the full edit and listen before sharing. Check that viewers can follow
   each transition without having to guess what happened between scenes. The
   tool does not judge whether narration is true.

This version runs a **batch of agent-authored actions**. The agent chooses
everything; the adapter does not plan or explore. Interactive per-action CLI
sessions, MCP, popups, iframe targeting, and arbitrary JavaScript actions are
not implemented.

See [Project format](project-format.md) for EDN, actions, persistent camera
instructions, editable cursors, cuts, and presentation timing.

## Troubleshooting

| Symptom | Next step |
| --- | --- |
| Browser or recording helper missing | Verify Chrome or the configured executable; install only the documented recording helper after download approval. |
| Kokoro unavailable | Check the loopback endpoint or request the actual foreground launcher. Do not invent a command or substitute cloud speech. |
| Completion takes longer than 15 seconds | Set `:browser :timeout-ms` in the shooting recipe, for example `60000`. Capture the completed state, then cut measured loading intervals from the edit. Allowed range: 1–600000 ms. |
| Isolated browser shows sign-in | Run `auth` in an interactive terminal, verify the transferred session, and set `:browser :storage-state`. Use a new auth directory for expired sessions. |
| Selector fails or times out | Inspect the real UI and fix the shooting recipe with a unique selector and an explicit completion wait. Capture new footage only when the browser actions must change. |
| Unknown recorded source ID | Match edit `:source` to an ID in the supplied capture manifest, not an edit-instance ID. |
| Scene-local camera rejected | Use the version-2 edit schema; version 1 still requires a top-level camera track. |
| Narration exceeds footage | Shorten the narration, or recapture with a final stable checkpoint. |
| Caption exceeds two lines | Rewrite the sentence more concisely; do not split speech arbitrarily mid-sentence. |
| Presentation anchor falls inside a cut | Move the anchor to retained footage in the original scene timeline. |
| Output directory already exists | Choose a fresh directory. Preserve failed artifacts for diagnosis. |
| Old zoom configuration rejected | Replace `:zoom?` and scene-level `:focus` using the migration instructions in the format guide. |

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
- `project.edn`: project copy without browser configuration or the Kokoro launch command.
- `capture.edn`: absolute source path, encoded scene bounds, actions, source-time
  cursor events, and clock-calibration anchors.
- `raw/*.webm`: original private recording, including synchronization/setup.

Render directory:
- `demo.mp4`: shareable only after review.
- `project.edn`, `timeline.edn`: presentation decisions, source-scene references, and timings.
- Per-cue WAVs, caption PNGs, lossless alpha cursor MOVs, intermediate scene videos.
- `verification.edn`: render metadata and outstanding review checks.

Scene markers calibrate monotonic input timestamps to source-video time.
The cursor track follows source footage through cuts and reuse; presentation
settings follow the edited timeline like the camera. Source video contains no
injected pointer or click pulse. Capture manifests use absolute paths: update
`:source` if deliberately relocating footage.

Keep raw footage, authentication state, logs, and configuration private.
New capture/render directories use owner-only permissions (0700) on POSIX
systems; other platforms inherit filesystem ACLs, which you must check.
Existing directories are not retroactively secured by the CLI.

Saved project copies omit `:browser` and `:kokoro :command`; reapply those
settings from private configuration if you need to capture again or launch
speech. Actions, narration, URLs, selectors, and absolute source/audio paths
remain in manifests because rendering and capture validation use them.
**These manifests are not sanitized exports.** Unknown project metadata may
also remain: do not add secrets to project files.

The CLI does not print raw exception details or subprocess output on failure.
It rejects navigation URLs with embedded user/password credentials and
refuses to fill HTML password inputs during capture. These guards do not
detect secrets in query strings, ordinary text inputs, or page content.
Authenticate outside the recording using a private storage-state file.
Browser downloads are disabled.

The tool does not upload anything, redact recordings, or package artifacts
for sharing. EDN cannot execute code through the reader, but a configured
Kokoro command and browser executable **are executable configuration**.
Use trusted project and capture files: this CLI is not a sandbox.

Only publish a reviewed final MP4. Check visible names, email addresses,
notifications, account menus, and captions. Keep custom configuration in
`work/` or a `*.local.edn` file, and keep authentication outside the repository.
Ignore rules are a backstop, not a guarantee: Git can still force-add files.
See [Security and privacy](../SECURITY.md) for boundaries and audit limits.

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
| `cursor` | Pure cursor validation, clock mapping, source sampling, persistent edit settings |
| `cursor-render` | AWT cursor rasterization and streaming lossless alpha-video encoding |
| `motion-blur` | Independent exposure validation, sampling clock, discontinuity boundaries, and linear-light resolve filters |
| `auth` | Manual login, fresh-context verification, and private state export |
| `capture` | Playwright browser adapter and encoded boundary extraction |
| `speech` | Local HTTP and owned-process lifecycle |
| `render` | Caption rasterization and FFmpeg composition |
| `io` | EDN files and external process boundary |
| `main` | CLI and local fixture server |

The rendering plan is ordinary immutable EDN. No browser handles, subprocesses,
or mutable agent session state enter the planner. Java AWT rasterizes caption
images so the renderer does not depend on FFmpeg's optional libass/drawtext
filters. Java Playwright is the browser bridge; the only JavaScript is the
small capture hook (cursor hiding, click observation, sync markers) and browser
evaluation needed by the adapter.

## Tests

```sh
clojure -M:test
clojure -M:test-zoom
clojure -M:test-cuts work/cut-check-v1
clojure -M:test-edit work/edit-check-v1
clojure -M:test-cursor work/cursor-check-v1
clojure -M:test-blur work/blur-check-v1
clojure -M:smoke work/acceptance-v1
```

The blur regression requires FFmpeg, not Chrome or Kokoro. It checks rendered
cursor trails, camera motion, settled holds, cuts, zero-exposure identity, and
unchanged source footage. Optional top-level `:motion-blur` settings are described
in [Project format](project-format.md#motion-blur). Keep the sharp edit as a
baseline and reuse the same footage/audio when comparing; blur costs additional
render time and temporary storage.

The cursor regression requires Chrome and FFmpeg, not Kokoro. It captures a
button that initially blocks clicks, checks clean pixels and click/video-clock
alignment, then checks rendered cursor style/size/visibility/click effects,
cuts, reordering/reuse, camera transforms, checkpoint holds, and unchanged
source hashes. Its audio is a synthetic tone, not spoken narration.

The edit regression renders synthetic colored footage and checks source
reordering, reuse, omission, cuts, local camera tracks, and unchanged source
hashes across two exports. It uses synthetic audio and needs FFmpeg, not
Chrome or Kokoro. Unit tests also verify version-1 migration equivalence.

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
