# Milestone 6 — Interactive Corner-Drag Calibration

**Goal (brief Step 3, calibration UI):** render the fitted wireframe over the box with interactive
2D-to-3D corner handles for manual fine-tuning.

## Approach
In phase `RESULT` the box is an **editable cuboid**: fixed PCA axes + center (carried by the anchor)
with **mutable half-extents**. The 8 wireframe corners are live handles.

Drag math (`MeasureViewModel.onResultPick` / `onResultDrag`):
1. **Pick** — on touch-down, select the corner whose projected screen position is nearest the touch
   (within `CORNER_PICK_PX`); it renders filled + enlarged.
2. **Drag** — project the screen drag onto each OBB axis's *outward* screen direction (computed from
   the current view-projection), pick the best-aligned axis, and convert the drag (px) to meters via
   that axis's on-screen scale. Resize that dimension **symmetrically about the center** — "drag
   outward = grow, inward = shrink," regardless of which corner you grabbed. Half-extents clamp to
   [1 cm, 2 m].

The `ResultInfo` readout and HUD update live as you drag; the anchor keeps the box glued to the
physical object.

## Key files
- `MeasureViewModel.kt` — `onResultPick`, `onResultDrag`, `onResultDragEnd`, `cornersFrom`,
  `projScreen`; editable state `editAxes` / `editHalf` / `selectedCorner`.
- `ui/MeasureScreen.kt` — RESULT-phase drag layer (`detectDragGestures`), selected-corner highlight
  in `WireframeOverlay`, `ResultInfo` readout.

## Verification (on device)
In RESULT, grab a corner (it enlarges) and drag: the matching side grows/shrinks, and the `cm`
readout + `CORNER_ADJUST` logcat line update. **Clear** resets to SEARCHING.

## Notes / limits (quick-finish scope)
- Resize is **symmetric about the center** (simple, keeps the anchor valid) rather than
  opposite-corner-fixed; good for correcting a slightly over/under-sized dimension.
- Textureless-face edge fallback (Canny/Sobel) from the brief's §3 is not wired here — the depth +
  hull already handle plain cardboard acceptably; it can layer into `DepthFusion` later.
