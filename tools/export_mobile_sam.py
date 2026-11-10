#!/usr/bin/env python3
"""Export MobileSAM (`mobile_sam.pt`) to two ONNX files for on-device ARTest3 inference.

Outputs (into ../app/src/main/assets/):
  - mobile_sam_encoder.onnx : input_image [1,3,1024,1024] float -> image_embeddings [1,256,64,64]
  - mobile_sam_decoder.onnx : embedding + point prompt -> masks [1,K,H,W], iou_predictions, low_res

The Kotlin `SegmentationInferenceEngine` does SAM's ResizeLongestSide(1024) + ImageNet
normalization on-device, so the encoder here takes the ALREADY-preprocessed 1024x1024 tensor
(no preprocessing baked into the graph). Tensor names below MUST match the Kotlin constants.

Run in the Yolo-test uv env (has torch); MobileSAM package is required:
    pip install git+https://github.com/ChaoningZhang/MobileSAM.git
    python export_mobile_sam.py
"""
import os
import torch


def inline_weights(path: str) -> None:
    """Fold any external-data (`.onnx.data`) weights back into a single self-contained file.

    The Android engine loads the model from a byte array (`env.createSession(bytes)`), which does
    NOT resolve an external-data sibling — so the asset must be self-contained.
    """
    import onnx

    model = onnx.load(path)  # resolves external data if present
    onnx.save_model(model, path, save_as_external_data=False)
    data = path + ".data"
    if os.path.exists(data):
        os.remove(data)

HERE = os.path.dirname(os.path.abspath(__file__))
# mobile_sam.pt lives in the sibling Yolo-test project (see repo memory).
CHECKPOINT = os.environ.get(
    "MOBILE_SAM_CKPT",
    os.path.abspath(os.path.join(HERE, "..", "..", "Yolo-test", "mobile_sam.pt")),
)
ASSETS = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets"))
OPSET = 17


def main() -> None:
    from mobile_sam import sam_model_registry
    from mobile_sam.utils.onnx import SamOnnxModel

    os.makedirs(ASSETS, exist_ok=True)
    print(f"checkpoint: {CHECKPOINT}")
    print(f"assets out: {ASSETS}")

    sam = sam_model_registry["vit_t"](checkpoint=CHECKPOINT)
    sam.eval()

    # ---- Image encoder: preprocessed 1024x1024 tensor -> 256x64x64 embedding ----
    encoder_path = os.path.join(ASSETS, "mobile_sam_encoder.onnx")
    dummy_image = torch.randn(1, 3, 1024, 1024, dtype=torch.float)
    torch.onnx.export(
        sam.image_encoder,
        dummy_image,
        encoder_path,
        input_names=["input_image"],
        output_names=["image_embeddings"],
        opset_version=OPSET,
        do_constant_folding=True,
        dynamo=False,   # classic (TorchScript) exporter — the tested SAM path; dynamo mishandles
                        # SAM's dynamic axes and can emit a weightless graph.
    )
    inline_weights(encoder_path)   # make it byte-array loadable on Android (no external .data)
    print(f"wrote {encoder_path}")

    # ---- Prompt/mask decoder (SamOnnxModel wrapper) ----
    onnx_model = SamOnnxModel(sam, return_single_mask=False)
    embed_dim = sam.prompt_encoder.embed_dim               # 256
    embed_size = sam.prompt_encoder.image_embedding_size   # (64, 64)
    mask_size = [4 * embed_size[0], 4 * embed_size[1]]      # (256, 256)

    dummy = {
        "image_embeddings": torch.randn(1, embed_dim, *embed_size, dtype=torch.float),
        "point_coords": torch.randint(low=0, high=1024, size=(1, 2, 2), dtype=torch.float),
        "point_labels": torch.randint(low=0, high=4, size=(1, 2), dtype=torch.float),
        "mask_input": torch.randn(1, 1, *mask_size, dtype=torch.float),
        "has_mask_input": torch.tensor([1], dtype=torch.float),
        "orig_im_size": torch.tensor([1080, 1920], dtype=torch.float),
    }
    decoder_path = os.path.join(ASSETS, "mobile_sam_decoder.onnx")
    torch.onnx.export(
        onnx_model,
        tuple(dummy.values()),
        decoder_path,
        input_names=list(dummy.keys()),
        output_names=["masks", "iou_predictions", "low_res_masks"],
        dynamic_axes={
            "point_coords": {1: "num_points"},
            "point_labels": {1: "num_points"},
        },
        opset_version=OPSET,
        do_constant_folding=True,
        dynamo=False,   # classic exporter honors dynamic_axes; dynamo rejects them for SAM.
    )
    inline_weights(decoder_path)
    print(f"wrote {decoder_path}")
    print("done. Verify encoder output is [1,256,64,64].")


if __name__ == "__main__":
    main()
