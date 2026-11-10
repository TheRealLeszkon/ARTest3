# Milestone 1 — Phase 1: Interactive 3D Anchoring

**Goal (brief Step 1):** the user taps the box; we segment it interactively on-device, project the
mask into 3D world space, fit a coarse oriented box, and drop a persistent AR World Anchor so the box
proxy tracks with zero drift as the camera moves.

## Theory

### 1. Tap → interactive segmentation (MobileSAM)
A tap is a **point prompt**. MobileSAM's image **encoder** turns the RGB frame into a `256×64×64`
embedding once; its prompt **decoder** turns (embedding + the tapped point) into a single instance
mask. This is class-agnostic — it segments a plain cardboard box, which the old YOLO/COCO path could
not (COCO has no box class; that failure is why this project switched to SAM).

Preprocessing is SAM's `ResizeLongestSide(1024)`: resize the long image side to 1024, normalize by
ImageNet mean/std, pad bottom/right to 1024². The tap point is scaled by the same factor into the
1024 frame; `orig_im_size` tells the decoder to upscale the output mask back to camera-image pixels.

Thermal rule: the encoder (the expensive part) runs **only on a tap**, never on live frames. Between
taps we rely on ARCore VIO.

### 2. Mask → 3D (depth back-projection)
For each depth pixel inside the mask, invert the pinhole model to a camera-frame point and transform
by `world_T_camera` to WORLD. Uses the depth-resolution intrinsics (RGB intrinsics scaled down — the
depth image is lower-res with its own effective intrinsics).

### 3. Coarse OBB (PCA)
Strip statistical outliers (depth bleeds a little onto the floor), then PCA-fit an oriented box:
covariance eigenvectors = the object's own axes, min/max projection along each = the extents. An OBB
(not AABB) hugs a rotated box tightly.

### 4. World anchor + tracking
Create an ARCore anchor at the OBB center and freeze the 8 corners as world-frame offsets. ARCore
drift-corrects the anchor pose (loop closure, IMU/visual fusion), so recomputing `center + offset`
each frame keeps the wireframe glued to the physical box during a walk-around.

## Key files
- `segment/SegmentationInferenceEngine.kt` — MobileSAM encode/decode, ResizeLongestSide, NNAPI→CPU.
- `geometry/PointCloudBuilder.kt`, `OutlierFilter.kt`, `ObbFitter.kt`, `SymmetricEigen.kt`.
- `geometry/MultiViewGeometryPipeline.coarseObb` — the Phase-1 glue.
- `tracking/FrameExtractor.kt`, `AnchoredObb.kt`, `ARTrackingManager.kt`.
- `MeasureViewModel.kt` (orchestration), `ui/MeasureScreen.kt` (overlay).
- `tools/export_mobile_sam.py` — produces the two ONNX assets.

## Verification
See `docs/PROGRESS.md` → "On-device verification checklist (M1 go/no-go)". In short: export the
assets, build+install (verify `versionName`, grant camera in-app), tap a well-lit box, confirm the
silhouette + anchored wireframe, then walk 0°→90° and confirm the box stays glued.

## Known limitations (by design, resolved later)
- Dimensions are a **single-view PCA** estimate — not yet accurate; Phase 3 (M5) adds multi-view
  fusion + cuboid fit for that.
- Corner offsets are frozen in the world frame (no per-frame OBB re-orientation) — fine for
  stability; interactive corner-drag correction is M6.
- Textureless-surface depth gaps (plain cardboard) are handled in M4 (Canny/Sobel + depth fallback).
