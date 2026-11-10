# Milestone 5 — Phase 3c: 3D Box Fitting

**Goal (brief Step 3, part 3):** fit a 6-faced rectangular cuboid to the fused cloud and report
L/W/H in meters.

## Approach (`geometry/CuboidFitter.fit`)
1. **Robust inliers** — `OutlierFilter` drops stray depth that survived hull filtering (mean + 2σ).
2. **Orthogonal axes (PCA)** — `ObbFitter` gives three mutually-orthogonal principal axes. An OBB
   *is* a 6-faced rectangular cuboid, so the rectangular prior is enforced by construction.
3. **Robust dimensions** — each side length is the **2nd…98th-percentile span** of the points
   projected onto that axis (not raw min/max, which one outlier inflates), and the center is
   recentred to the middle of those spans. This is the outlier-resistant, least-squares-friendly
   analogue of the brief's LM dimension refine.
4. **Confidence** — blends inlier survival ratio with point-count adequacy → 0..1.

Result: `core/MeasurementResult` (L ≥ W ≥ H sorted for display, plus the fitted `Obb`, confidence,
point count).

## Wiring
`MeasureViewModel.measureFromHull` runs fusion (M4) + fit (M5) on the background thread after the
carve, then hands the result to the render loop, which **re-anchors** an ARCore World Anchor at the
refined center (`ARTrackingManager.anchor`) and enters phase `RESULT`. The green wireframe + the
`ResultInfo` readout (`L × W × H cm · confidence`) show the measured box, drift-tracked by the anchor.

## Verification (on device)
After 3 captures → carve → the box wireframe snaps to the **refined** cuboid and the bottom readout
shows `… × … × … cm · confidence NN%`. Logcat: `CUBOID_FIT L×W×H=… conf=… pts=…`. Check the cm
against a tape measure on a known box.
