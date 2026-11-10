# ML model assets

These `.onnx` files are **git-ignored** (large) — regenerate them, then drop them here:

- `mobile_sam_encoder.onnx` — image (1×3×1024×1024) → embedding (1×256×64×64)
- `mobile_sam_decoder.onnx` — embedding + point prompt → mask + iou

Generate with:

```bash
cd ../../../../../tools           # test/ARTest3/tools
python export_mobile_sam.py       # run in the Yolo-test uv env (has torch/onnx)
```

Without these files the app still builds and runs — the tap-to-segment path reports
"SAM model missing" until the assets are present (see `SegmentationInferenceEngine`).
