# Milestone 2 — Phase 2: Multi-View Capture System

**Goal (brief Step 2):** after Phase 1 anchors the box, capture 3 keyframes at ~0°/45°/90° around
it, recording everything the geometry pipeline needs, and guide the user with a state machine + UI.
Guard against captures taken from the same angle (insufficient parallax).

## Design

### State machine (`core/CaptureState`, `CaptureStage`)
`ANGLE_0 → ANGLE_45 → ANGLE_90`. The **front view is auto-recorded** as keyframe 0 the moment the
Phase-1 anchor is created (it's the view the user just segmented), so the machine starts on
`ANGLE_45`. The user walks around and presses the shutter for the remaining two.

### Per-capture recording (`MeasureViewModel.captureKeyframe`)
On each shutter press, on a frame carrying both a camera image and depth:
1. **Parallax guard** — reject if the camera's yaw around the box centroid is within
   `MIN_PARALLAX_DEG` (20°) of *any* already-captured view.
2. **Re-segment** — project the anchor center into this view and use it as the MobileSAM point
   prompt (each viewpoint has a different silhouette, so we must re-segment, not reuse). Encoder runs
   only here — a keyframe moment — honoring the thermal rule.
3. **Record** a `Keyframe`: RGB (ARGB), pose `Tᵢ`, intrinsics `Kᵢ` (RGB-res, derived from the
   depth-res intrinsics × resolution ratio), the SAM `Mask`, the `DepthMap` (+ its own intrinsics),
   and the view-projection — into the thread-safe `KeyframeCache`.
4. Advance the stage; at 3/3 → `CAPTURED` (ready for Phase 3 / M3).

### Guidance UI (`ui/MeasureScreen`)
- A **dial** with ticks at 0°/45°/90° (filled green as each is captured) and a live needle at the
  camera's current yaw; the needle turns green when the current view has parallax.
- A **shutter** button (green ring when it's safe to shoot).
- HUD lines: `Views n/3 · next <stage>`, `Yaw cur° → target° · OK|move`.

## Yaw / parallax math
`yaw = atan2(camX − cX, camZ − cZ)` in the horizontal (XZ) plane around the anchor center; angular
differences wrapped to 0..180°. The first view defines the 0° reference; the HUD/needle show yaw
relative to it.

## Key files
- `core/Keyframe.kt` (`Keyframe`, `KeyframeCache`, `CaptureStage`, `CaptureState`).
- `MeasureViewModel.kt` — `onShutter`, `captureKeyframe`, `hasParallax`, `yawDeg`, `projectWorldToImage`.
- `ui/MeasureScreen.kt` — `CaptureGuidanceRing`, `ShutterButton`, HUD capture lines.

## Verification (on device, after M1 passes)
1. Anchor a box (M1). HUD shows `Phase: CAPTURING`, `Views 1/3 · next Corner (45°)`.
2. Walk around; the needle sweeps and the `Yaw cur°→target°` updates; shutter ring goes green past
   ~20° from the front view.
3. Press shutter at ~45°, then ~90° → `Views 2/3`, `3/3`, banner "3/3 captured — fusion lands in M3".
4. Try shooting twice from the same spot → rejected with "Too close to a captured view"
   (`CAPTURE_REJECT` in logcat). Confirm `KEYFRAME_CAPTURED stage=… total=…` logs per good shot.

## Not yet (later milestones)
- Encoder-embedding caching per keyframe (perf) — optional follow-up.
- Consuming the keyframes: visual hull (M3), depth fusion (M4), cuboid fit (M5).
