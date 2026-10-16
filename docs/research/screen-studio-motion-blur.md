# What Screen Studio's “optics-based motion blur” means

## Finding

**The inspected renderer approximates exposure along a motion trail, rather
than simply softening the image with a generic blur.** We can implement that
principle independently in Agent Demo. We have not matched Screen Studio's
output or added motion blur to Agent Demo yet.

This conclusion comes from static inspection of the supplied **4.0.4-4926
Apple Silicon DMG**, specifically its packaged JavaScript/GPU renderer. It is
not a claim that we executed or disassembled its native recorder.

## What the packaged code does

The main evidence is `dist/CF-SEWYJ.js` inside the extracted ASAR:

1. **Keeps current and previous transforms.** Its motion timeline provides
   current and previous-frame styles. Rectangle and rotating-rectangle models
   carry both states, including camera/perspective transforms.
2. **Constructs a swept trail.** Blur strength interpolates from the current
   rectangle toward its previous state. The renderer accounts for position,
   dimensions, and rotation, not just a horizontal cursor smear.
3. **Handles coverage along that trail.** GPU helpers fit a quadratic to signed
   distances evaluated at the beginning, middle, and end of the trail. They
   split the integration interval and integrate clamped coverage. This fits
   **edge coverage**, not evidence of reconstructing a quadratic cursor path.
4. **Samples texture colors along covered intervals.** Sampling density depends
   on projected movement and a quality budget. Accumulated color is normalized
   by accumulated weight. There are separate static and subpixel-motion paths.
5. **Treats transparency and color deliberately.** Helpers convert colors into
   an approximate linear-light, premultiplied-alpha representation before
   accumulation. The observed square/square-root conversions approximate gamma
   2; they are not a full exact sRGB transfer function. This is materially
   different from stacking translucent cursor copies with ordinary alpha-over.
6. **Applies the machinery to multiple layers.** Cursor, screen, and webcam
   frame-building paths supply motion-blur parameters. Screen code suppresses
   blur in an instant-zoom snap window. This does not establish that every
   animation inside captured video receives motion-aware blur.

These observations go beyond feature-name strings: recovered property names,
function bodies, and embedded TypeGPU shader metadata connect the settings,
geometry, coverage integration, and texture sampling. Runtime reachability and
visual correctness remain untested.

### Exposure interpretation

The strength helper scales the animation amount by playback frame rate divided
by 60. The motion timeline reads a previous-frame state, and trail construction
uses that strength to scale the displacement toward it.

**Inference:** for constant-speed motion, this behaves approximately like an
exposure of `amount / 60` seconds. At amount 1, that is about 16.7ms: half a
30fps frame interval or one 60fps interval. This is an interpretation of the
math, not an explicitly recovered physical shutter setting or proof that all
render modes use the same effective exposure.

Faster movement consequently produces a longer trail; stationary material can
stay sharp. That is a defensible camera-exposure meaning for “optics-based.”
The examined path does not establish full lens/ray simulation. Separate loupe
code has glass/refraction-related controls; those should not be conflated with
this motion-blur mechanism.

### Quality/performance trade-offs

A packaged quality table assigns motion-blur budgets of 16 for export/paused
views and 6 for playback, with different texture-fetch spacing. The sampling
code treats the quality value as a fetch budget, so these are **not guarantees
of exactly 16 or 6 whole-frame temporal samples**. Shader paths also include
jitter/dithering and specialized handling of outlines, coverage, and shadows.

## How we can reproduce the principle

Use our own exposure-integration implementation, not transplanted vendor code:

1. Add an explicit exposure duration/strength and a bounded quality setting.
2. Evaluate continuous cursor position at several times within the exposure
   interval. Include the camera transform at each sample when it is moving.
3. Render each sample independently; accumulate weighted color and coverage in
   linear light with premultiplied alpha, then normalize and composite once.
   Repeated ordinary alpha-over draws would darken/overweight overlaps.
4. Keep static frames sharp and use more samples only when movement requires it.
5. Clip exposure windows at edit cuts, source-instance boundaries, and pointer
   teleports. Do not create trails across omitted footage or reused scenes.
6. Keep captions outside the blurred composition. At checkpoint holds, stop
   cursor motion; a camera transition during a hold can still need blur.

**Cursor-only blur is the smaller first step.** Our cursor already exists as a
separate render layer. However, its current source-time sampler snaps to 30fps,
so merely painting it repeatedly within one frame would not work: we must
separate continuous motion sampling from source-video frame selection.

**Camera/screen blur needs additional compositor work.** Blurring the cursor
before one camera transform cannot reproduce motion caused by a moving camera.
We need subframe transforms or an independently implemented motion-aware shader.
Temporal supersampling is a practical reference implementation, but it will not
automatically match their analytic coverage, filtering, or performance.

Suggested tests: zero-motion identity; trail length proportional to speed and
exposure; no brightness buildup or dark transparent edges; no trails across
cuts/teleports; clean settled frames; camera-plus-cursor motion; convergence as
sample quality increases. Compare visual output against a legitimately activated
Screen Studio instance before claiming close visual parity.

## Evidence and limitations

- Renderer SHA-256:
  `a6ac1fefd674b9f07860821252b6aac61087ce4ddd89a6d4013eb69cf21bb6dd`.
- REA 6.5.0 evidence:
  `ev_22640eb92e0df472f567ea62d5a66acf4da713b5ca14c7526b52599d3c7a53df`.
  The selected renderer hit REA's maximum-call-stack constraint during analysis;
  its graph is incomplete and is not the sole basis of these conclusions.
- Supplementary recovery parsed a 2,896-entry literal string table and solved
  its bounded numeric rotation constraint (rotation 401), then substituted
  literal lookups. No target JavaScript, obfuscator bootstrap, native module,
  or app was executed. Alias substitution is textual, not a complete
  scope-sensitive decompiler; focused paths were inspected directly.
- The original DMG's 26 ASAR integrity contradictions remain unresolved. See
  [the parity investigation](screen-studio-4.0.4-parity.md) for artifact identity
  and the separate successful Apple signature check.

Original character-offset anchors in `dist/CF-SEWYJ.js`:

| Offset | Evidence |
| --- | --- |
| 394818 | Rectangle motion model, trail construction and stationary checks |
| 397098 | Motion timeline and previous-frame state access |
| 400524 | Motion-blur parameter constructor |
| 404218 | Export/playback quality tables |
| 415585 | Enable/amount/frame-rate strength calculation |
| 427889, 428576, 435229 | Approximate linear-light/PMA and display conversions |
| 436063 | Texture-segment sampling and normalization |
| 459751, 463754, 471748 | Quadratic distance fit, coverage ramp, clamped integration |
| 528032, 816209 | Shader dependencies connecting coverage, sampling, and weighting |
| 609612, 736033, 752692 | Cursor, screen, and webcam motion-blur parameters |
| 740033 | Screen path's blur-strength call, including snap-window suppression |

Private evidence remains under ignored `work/screen-studio-research/`:
`motion-blur-evidence.json`, `blur-decode-receipt.json`, the decoder script,
and focused excerpts. Recovered proprietary source is not included in this
report or added to Agent Demo's implementation.
