# ARTest3 (AR Measure)

An Android experiment in measuring a box entirely on the phone with AR and on-device ML.

1. **Select:** tap the object. MobileSAM (ONNX Runtime) segments it, and a coarse box is anchored in the world with ARCore.
2. **Capture:** take three guided views (0, 45 and 90 degrees, at least 20 degrees apart). Each view is re-segmented.
3. **Measure:** visual-hull voxel carving intersects the three silhouettes, depth is fused inside the hull, and a cuboid is fitted. Corners can be dragged to correct it.

## Status

All six milestones are code-complete and the app builds and installs, but the full flow has not been verified on a device. The MobileSAM ONNX files are not included. Export them first:

```bash
cd tools
pip install git+https://github.com/ChaoningZhang/MobileSAM.git
python export_mobile_sam.py   # writes mobile_sam_encoder.onnx / mobile_sam_decoder.onnx into app assets
```

See `PLAN.md` for the roadmap and `docs/` for each milestone.
