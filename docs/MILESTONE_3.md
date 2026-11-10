# Milestone 3 — Phase 3a: Multi-View Visual Hull

**Goal (brief Step 3, part 1):** raycast and intersect the extruded 3D cones from the 3 mask views
(intersection of visual hulls) to constrain the object volume before depth fusion.

## Theory

Each captured view's 2D silhouette (the SAM `Mask`) extrudes into a cone in world space from that
camera's center. The real object must sit inside **every** view's cone, so the object volume is
bounded by the **intersection** of the cones. This is depth-independent — it works even where the
hardware depth is noisy/missing — and it gives depth fusion (M4) a clean "is this point background?"
test.

We compute it by **voxel carving**:
1. Voxelize a world grid over a seed region — the Phase-1 coarse OBB, expanded (45% + 8 cm margin).
   The grid auto-coarsens to stay under a 2M-voxel cap.
2. Keep a voxel iff its center projects **inside the mask of every view**. Projection reuses each
   keyframe's `viewProjection` (world→clip) → normalized screen → image pixel (the same portrait
   rotate-90 mapping taps use). Mask membership is sampled with a 1-pixel tolerance so
   silhouette-edge / small pose drift doesn't punch holes.
3. The kept voxels are the hull: an occupancy grid (with a `contains(x,y,z)` test for M4) + the
   packed voxel centers (world points for M5 + the overlay) + tightened world bounds.

## Key files
- `core/VisualHull.kt` — per-view cone (viewProjection + mask + apex).
- `geometry/VisualHullIntersector.kt` — `intersect(hulls, boundsMin, boundsMax)` → `Hull`.
- `geometry/MultiViewGeometryPipeline.kt` — `carveHull(keyframes, boundsMin, boundsMax)`.
- `MeasureViewModel.carveVisualHull` — runs on the 3rd capture; seeds bounds from the coarse OBB
  (`expandedAabb`); streams downsampled voxel centers to the UI.
- `ui/MeasureScreen.kt` — `HullOverlay` (world-locked orange voxel cloud) + HUD `Hull: N vox · w×h×d cm`.

## Verification (on device, after M2)
1. Anchor a box (M1), capture 3 views at 0°/45°/90° (M2). On the 3rd shutter the banner shows
   "Carving visual hull…" then "Visual hull: N voxels — depth fusion is M4".
2. An **orange voxel cloud** appears filling the box's volume and **stays locked to the box** as you
   move (it's world points reprojected each frame). HUD shows `Hull: N vox · w×h×d cm` — the cm
   should roughly match the box.
3. Logcat `HULL_CARVED voxels=… voxel=…cm extent=…` per carve. If the masks/angles were poor:
   `HULL_EMPTY` + "recapture with cleaner, wider angles".

## Notes / limits
- The hull over-estimates concavities (that's inherent to visual hulls) — depth fusion (M4) trims it
  to the measured surface, and the cuboid fit (M5) turns it into L/W/H.
- Carve cost is a few hundred ms once, on a background thread (single-flight with the SAM engine).
