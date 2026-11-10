# ARTest3 (AR Measure) — Progress & Session Handoff

> **Read this first each session.** Living status for the on-device AR box-measurement app.
> `PLAN.md` = the roadmap (modules + milestones). This file = *where we are* + resume steps.

_Last updated: 2026-08-09 — **ALL 6 milestones (Phases 1–3 + calibration) code-complete; builds
clean (JDK 17, 93 MB arm64-v8a APK with models). Full pipeline: tap→SAM→anchor → 3-view capture →
visual hull → depth fusion → cuboid fit (L/W/H + confidence) → corner-drag calibration. M1 installs +
launches on device; the tap/capture/measure flow is NOT yet human-verified on device (remaining work
is on-device accuracy tuning, not new code).**_

---

## What this is

Standalone Android app (own git repo, **not** tracked by the `test/` superproject), package
`com.example.armeasure`, app **"AR Measure"**, `versionName 0.1-poc`. 100% on-device: ARCore
(via SceneView) for tracking/depth, MobileSAM (ONNX Runtime) for tap-to-segment, native Kotlin for
geometry. Three-phase pipeline (interactive anchor → multi-view capture → geometry fusion); this
increment delivers **Phase 1** and the module skeleton for Phases 2–3.

## Milestone status

| # | Milestone | State |
|---|---|---|
| M1 | Phase 1 — tap → SAM mask → coarse OBB → world anchor → live wireframe | **Built + installed.** Human walk-around pending |
| M2 | Phase 2 — 3-view capture state machine + guidance dial/shutter + parallax guard | **Built.** Not device-verified |
| M3 | Phase 3a — visual-hull intersection (voxel carve + world-locked overlay) | **Built.** Not device-verified |
| M4 | Phase 3b — hull-filtered depth fusion | **Built.** Not device-verified |
| M5 | Phase 3c — cuboid fit (PCA axes + robust extents) → L/W/H + confidence | **Built.** Not device-verified |
| M6 | Interactive corner-drag calibration (RESULT phase) | **Built.** Not device-verified |

State legend (per ARTest2 convention): Implemented → **Built** → Installed → Verified on device →
Physically validated → Accuracy validated.

## Architecture

```
MainActivity                     camera-permission gate → MeasureScreen
 └─ MeasureViewModel             only ARCore/SAM talker; owns MeasurementUiState; Phase-1 pipeline
     ├─ tracking/ARTrackingManager   session config (Depth AUTO) + world anchor
     │   ├─ FrameExtractor           per-frame VP, camera pose, raw depth + depth intrinsics
     │   ├─ YuvImage                 SDK-free YUV snapshot + BT.601 YUV→ARGB (off render thread)
     │   └─ AnchoredObb              ARCore anchor (center) + frozen world-frame OBB corners
     ├─ segment/SegmentationInferenceEngine   MobileSAM encoder(1024²→256×64×64)+decoder(point→mask)
     ├─ geometry/MultiViewGeometryPipeline    coarseObb (Phase 1) + measure() (Phase 3, stubs)
     │   ├─ PointCloudBuilder · OutlierFilter · ObbFitter · SymmetricEigen   (implemented)
     │   └─ VisualHullIntersector · DepthFusion · CuboidFitter               (stubs, M3–M5)
     └─ core/                        CameraIntrinsics/Pose, DepthMap, Mask, Obb, Keyframe,
                                     CaptureStage/State, VisualHull, MeasurementResult
 ui/MeasureScreen                 ARScene + tap + mask silhouette + OBB wireframe + callout + HUD
```

**Phase-1 flow:** tap (portrait screen) → queue → on a frame with camera image + depth, snapshot →
background: `YuvImage.toArgb` → `SegmentationInferenceEngine.segment` (tap mapped to image px:
`imgX=ny·iw`, `imgY=(1−nx)·ih`) → `MultiViewGeometryPipeline.coarseObb` (back-project masked depth →
outlier filter → PCA OBB) → render loop: `ARTrackingManager.anchor` drops the world anchor →
`MEASURED`; wireframe reprojected each frame from the live anchor pose. Thermal rule honored: SAM
runs only on tap, never on live frames.

**Phase-2 flow (M2):** anchoring auto-records the front view as keyframe 0 → phase `CAPTURING`. The
user walks around; a dial + needle show yaw vs the 0°/45°/90° targets and the shutter ring greens
when parallax ≥ 20°. Each shutter press re-segments the box (SAM prompt = anchor center projected
into that view), runs the **insufficient-parallax guard**, and records a `Keyframe` (RGB, Tᵢ, Kᵢ,
mask, depth) into `KeyframeCache`. At 3/3 → phase `CAPTURED`. See `docs/MILESTONE_2.md`.

**Phase-3 flow (M3→M6):** the 3rd capture triggers, on one background job:
`carveVisualHull` (M3, voxel-carve the intersection of the 3 silhouette cones over the expanded
coarse-OBB region) → `DepthFusion.fuse` (M4, back-project masked depth, keep points inside the hull)
→ `CuboidFitter.fit` (M5, outlier removal + PCA orthogonal axes + robust percentile extents →
`MeasurementResult`). The render loop **re-anchors** at the refined center → phase `RESULT`: green
wireframe + `ResultInfo` readout `L×W×H cm · confidence`. **M6:** drag any corner handle to resize
that dimension (drag projected onto the OBB axes, symmetric about center); **Clear** resets. See
`docs/MILESTONE_3..6.md`.

## Coordinate discipline (labelled in code)

- SCREEN(normalized, portrait) → camera-image pixel (landscape sensor): `imgX = ny·iw`,
  `imgY = (1−nx)·ih` (portrait rotate-90; same mapping proven in ARSelectionTest/ARDepth).
- depth pixel → CAMERA (pinhole) → WORLD (× `world_T_camera`) for the cloud/anchor.
- WORLD → clip (× VP) → NDC → SCREEN for every overlay.
- Depth intrinsics = RGB `imageIntrinsics` **scaled to depth resolution** (never RGB fx/cx on depth).

## Resume / build

```bash
export JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home
./gradlew :app:assembleDebug          # clean; ~37 MB arm64-v8a APK
```

- Device = OnePlus **CPH2805** (adb id 3C164V007MW00000). adb link is **flaky** — retry installs and
  **verify `versionName=0.1-poc`** after install (install can finalize on the OLD build).
- Camera: grant **in-app** (device blocks `adb pm grant CAMERA` with SecurityException).

## ⚠️ Model assets required before Phase-1 runtime

The two ONNX files are **git-ignored** and NOT bundled — the app builds/runs without them but
tap-to-segment reports "SAM model missing" until they exist:

```bash
cd tools
pip install git+https://github.com/ChaoningZhang/MobileSAM.git   # export env only (Yolo-test uv)
python export_mobile_sam.py            # reads ../../Yolo-test/mobile_sam.pt
# → app/src/main/assets/mobile_sam_encoder.onnx  (verify output [1,256,64,64])
# → app/src/main/assets/mobile_sam_decoder.onnx
```

**Not yet run** (needs the MobileSAM pip package + network). Tensor I/O names in the script MUST
match the constants in `SegmentationInferenceEngine` (`input_image`/`image_embeddings`/
`point_coords`/`point_labels`/`mask_input`/`has_mask_input`/`orig_im_size`/`masks`/`iou_predictions`).

## On-device verification checklist (M1 go/no-go)

Well-lit room, single box on the floor:
1. Export assets → rebuild → install → confirm `versionName=0.1-poc`, grant camera.
2. HUD shows `Depth API: supported`, `SAM: ready · NNAPI|CPU`, `Tracking: TRACKING`.
3. **Tap the box** → cyan silhouette hugs it (~1.5 s) → green OBB wireframe anchors at its center;
   HUD shows box `w×h×d cm`, `IoU`, `enc/dec ms`.
4. **Walk 0°→90° around it** → the wireframe stays glued (anchor drift-corrected). This is the
   go/no-go: SAM mask + world anchor stable.
5. Sanity-check the `cm` against a tape measure (rough — Phase 3 is what makes it accurate).

## Common issues / gotchas

- `SAM: missing` in HUD → assets not exported (see above).
- The encoder export can write **external weights** (`mobile_sam_encoder.onnx.data`); the engine
  loads from a byte array, which can't resolve that sibling → the export script inlines weights into
  a single self-contained file (`inline_weights`). If you hand-export, do the same or it fails at load.
- Encoder NNAPI may reject SAM ops → auto-falls back to CPU (slower first tap; logged `SegEngine`).
- Silhouette overlay is a **2D snapshot** at tap time — intentionally cleared 1.5 s after lock so it
  doesn't linger in the wrong place once the phone moves; the 3D wireframe is the persistent truth.
- Coarse OBB is **PCA**, world-frame corner offsets frozen at anchor time (like ARSelectionTest's
  AABB) — good enough for Phase-1 stability; Phase 3 (M5) is where accuracy comes from.
