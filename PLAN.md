# ARTest3 (AR Measure) — Roadmap

Production-grade, **100% on-device** box-dimension measurement via AR + edge ML. Android-only
(Kotlin, ARCore via SceneView, ONNX Runtime). Package `com.example.armeasure`.

The measurement pipeline has three phases (from the brief). This project builds them as milestones
on top of a shared module skeleton (the four deliverable modules).

## The four modules

| Module (brief) | Package | State |
|---|---|---|
| `ARTrackingManager` | `tracking/` | ✅ session config (Depth AUTO), per-frame VP/pose/depth (`FrameExtractor`), world anchor (`AnchoredObb`) |
| `SegmentationInferenceEngine` | `segment/` | ✅ MobileSAM encoder+decoder on ONNX Runtime (point prompt), NNAPI→CPU |
| `MultiViewGeometryPipeline` | `geometry/` | ✅ coarse OBB (PCA) + visual hull (M3) + depth fusion (M4) + cuboid fit (M5) |
| `MeasurementUIOverlay` | `ui/` | ✅ ARScene + tap, mask silhouette, hull cloud, wireframe + callout, capture steps/shutter, corner-drag calibration |

## Phases → milestones

- **M1 — Phase 1: Interactive 3D anchoring.** ✅ code-complete. Tap → MobileSAM mask → back-project
  masked depth to WORLD → PCA coarse OBB → ARCore World Anchor → live wireframe reprojection.
  *Unverified on device (needs the ONNX assets + a walk-around).*
- **M2 — Phase 2: Multi-view capture.** ✅ code-complete. 3-stage state machine (0°/45°/90°) +
  guidance dial/shutter → `Keyframe` (RGB, Tᵢ, Kᵢ, SAM mask, depth) into `KeyframeCache`. Front view
  auto-recorded; re-segment per view via anchor-center prompt; insufficient-parallax guard (20°).
  *Follow-up: cache encoder embedding per keyframe (perf).*
- **M3 — Phase 3a: Visual hull.** ✅ code-complete. `VisualHullIntersector` voxel-carves the
  intersection of the 3 silhouette cones; carve runs on the 3rd capture, rendered as a world-locked
  voxel cloud + HUD extent.
- **M4 — Phase 3b: Depth fusion.** ✅ code-complete. `DepthFusion` — back-project masked depth,
  keep points inside the visual hull → clean multi-view surface cloud.
- **M5 — Phase 3c: Cuboid fit.** ✅ code-complete. `CuboidFitter` — outlier removal + PCA orthogonal
  axes (6-face prior) + robust percentile extents → `MeasurementResult` (L/W/H + confidence).
- **M6 — Calibration UI.** ✅ code-complete. Interactive corner-drag on the RESULT wireframe: grab a
  corner, drag to resize the matching dimension (drag projected onto OBB axes, symmetric about center).

**All 6 milestones code-complete. Full pipeline builds clean; on-device accuracy tuning pending.**

## Reuse provenance

- Proven **depth→world / world→screen** math + anchor pattern: ARSelectionTest (`ar/`, `cloud/`, `world/`).
- Proven **PCA OBB** (`ObbFitter`, `SymmetricEigen`, `OutlierFilter`): ARTest2 `measure/`, `util/`.
- MobileSAM upgrade over YOLO/COCO: fixes the "no box class" failure (see ARSelectionTest history).

See `docs/PROGRESS.md` for live status and `docs/MILESTONE_1.md` for Phase-1 theory + verification.
