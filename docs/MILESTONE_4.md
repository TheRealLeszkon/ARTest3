# Milestone 4 — Phase 3b: Depth Fusion

**Goal (brief Step 3, part 2):** filter the hardware depth point clouds using the visual-hull bounds
to remove background noise, leaving a clean multi-view surface cloud.

## Approach
For each captured keyframe, back-project its **masked** depth into WORLD space (reusing
`geometry/PointCloudBuilder.build` with the keyframe's depth-res intrinsics, pose, and SAM mask),
then keep only points that fall **inside the carved visual hull** (`Hull.contains(x,y,z)` from M3).
The per-view mask already limits to the object silhouette; the hull additionally removes floor /
background / neighbour depth that a single mask leaks. Points from all 3 views accumulate into one
world cloud.

## Key files
- `geometry/DepthFusion.kt` — `fuse(keyframes, hull)` → packed world points.
- Reuses `geometry/PointCloudBuilder`, `core/Keyframe` (depth + mask + pose + intrinsics),
  `VisualHullIntersector.Hull.contains`.

## Verification
Runs automatically after the M3 carve (banner "Fusing depth + fitting box…"). Logcat:
`DEPTH_FUSED points=N (hull-filtered)`. Feeds M5. Textureless faces (sparse depth) degrade
gracefully — the hull still bounds the volume, so M5 still fits.
