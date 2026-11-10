package com.example.armeasure.segment

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.armeasure.core.Mask
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **Module 2 of the brief.** Interactive tap-to-segment via **MobileSAM** on ONNX Runtime.
 *
 * Two models (both git-ignored assets, produced by `tools/export_mobile_sam.py`):
 *   - **encoder** (`mobile_sam_encoder.onnx`): a preprocessed `1×3×1024×1024` RGB tensor → a
 *     `1×256×64×64` image embedding. This is the expensive part — per the thermal rule it runs ONLY
 *     on a tap / keyframe capture, never on live frames.
 *   - **decoder** (`mobile_sam_decoder.onnx`): the embedding + a point prompt → an instance mask.
 *     Cheap; re-runnable per tap against a cached embedding (embedding caching lands in M2).
 *
 * Preprocessing follows SAM's `ResizeLongestSide(1024)`: resize the long side to 1024, normalize by
 * ImageNet mean/std, pad bottom/right to 1024². The tap point is scaled by the same factor into the
 * 1024 frame; `orig_im_size` tells the decoder how to upscale the mask back to camera-image pixels.
 *
 * If either asset is missing/incompatible, [ready] stays false and [segment] returns null — the app
 * still builds and runs (the UI shows "SAM model missing").
 *
 * Preprocessing mirrors ARSelectionTest's `detect/YoloSegDetector`'s NNAPI→CPU fallback + warmup.
 */
class SegmentationInferenceEngine(context: Context) {

    /** True once both models loaded; false means assets missing/incompatible (app still runs). */
    val ready: Boolean
    /** Execution backend actually used by the encoder, for the HUD ("NNAPI" or "CPU"). */
    val backend: String

    private val env = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private val encoderInputName: String
    private val encoderOutputName: String

    /** One SAM segmentation: the mask (camera-image pixel space) + predicted IoU + timings (ms). */
    class SegResult(val mask: Mask, val iou: Float, val encodeMs: Long, val decodeMs: Long)

    init {
        var ok = false
        var backendName = "—"
        var encInName = INPUT_IMAGE
        var encOutName = IMAGE_EMBEDDINGS
        try {
            val encBytes = context.assets.open(ENCODER_ASSET).use { it.readBytes() }
            val decBytes = context.assets.open(DECODER_ASSET).use { it.readBytes() }

            // Encoder: try NNAPI + a warmup (pays graph-compile now and PROVES NNAPI can execute the
            // graph); fall back to CPU if that throws. SAM's encoder has ops NNAPI may reject.
            var enc = buildSession(encBytes, tryNnapi = true)
            encInName = enc.inputNames.first()
            encOutName = enc.outputNames.first()
            backendName = try {
                warmupEncoder(enc, encInName); "NNAPI"
            } catch (t: Throwable) {
                Log.w(TAG, "Encoder NNAPI warmup failed → CPU", t)
                enc.close()
                enc = buildSession(encBytes, tryNnapi = false)
                encInName = enc.inputNames.first()
                encOutName = enc.outputNames.first()
                "CPU"
            }
            encoder = enc

            // Decoder: CPU only — it uses interpolation/gather ops that NNAPI does not accelerate,
            // and it is cheap anyway.
            decoder = buildSession(decBytes, tryNnapi = false)
            ok = true
            Log.i(TAG, "MobileSAM ready (encoder=$backendName)")
        } catch (t: Throwable) {
            Log.w(TAG, "MobileSAM unavailable (assets missing? run tools/export_mobile_sam.py): ${t.message}")
        }
        ready = ok
        backend = backendName
        encoderInputName = encInName
        encoderOutputName = encOutName
    }

    private fun buildSession(bytes: ByteArray, tryNnapi: Boolean): OrtSession {
        val opts = OrtSession.SessionOptions()
        if (tryNnapi) opts.addNnapi()
        return env.createSession(bytes, opts)
    }

    private fun warmupEncoder(session: OrtSession, inName: String) {
        val zeros = FloatArray(3 * SAM_SIZE * SAM_SIZE)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(zeros), longArrayOf(1, 3, SAM_SIZE.toLong(), SAM_SIZE.toLong()))
            .use { t -> session.run(mapOf(inName to t)).close() }
    }

    /**
     * Segment the object under a tap.
     *
     * @param rgb    packed ARGB, row-major [w]*[h] (camera-image pixel space, landscape sensor).
     * @param pointX,pointY the tap in the SAME image-pixel space.
     * @return the mask (image-pixel space) + IoU + timings, or null if not ready / no valid mask.
     */
    fun segment(rgb: IntArray, w: Int, h: Int, pointX: Float, pointY: Float): SegResult? {
        val enc = encoder ?: return null
        val dec = decoder ?: return null
        if (w <= 0 || h <= 0) return null

        // ResizeLongestSide(1024): scale the long side to 1024; pad the rest.
        val scale = SAM_SIZE.toFloat() / max(w, h)
        val newW = (w * scale).roundToInt().coerceIn(1, SAM_SIZE)
        val newH = (h * scale).roundToInt().coerceIn(1, SAM_SIZE)

        // ---- Encoder ----
        val t0 = SystemClock.elapsedRealtime()
        val input = preprocess(rgb, w, h, newW, newH)
        val embedding: OnnxTensor
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SAM_SIZE.toLong(), SAM_SIZE.toLong()))
            .use { inT ->
                val res = enc.run(mapOf(encoderInputName to inT))
                // Copy the embedding out into our own tensor so we can close the result.
                val emb = res.get(encoderOutputName).orElse(res.get(0)) as OnnxTensor
                val flat = emb.floatBuffer
                val arr = FloatArray(flat.remaining()).also { flat.get(it) }
                res.close()
                embedding = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(arr), longArrayOf(1, 256, 64, 64)
                )
            }
        val encodeMs = SystemClock.elapsedRealtime() - t0

        // ---- Decoder ----
        val t1 = SystemClock.elapsedRealtime()
        val result = embedding.use { emb -> runDecoder(dec, emb, pointX * scale, pointY * scale, h, w) }
        val decodeMs = SystemClock.elapsedRealtime() - t1

        return result?.let { SegResult(it.first, it.second, encodeMs, decodeMs) }
    }

    /** @return (mask, iou) picking the highest-IoU of the decoder's candidate masks, or null. */
    private fun runDecoder(
        dec: OrtSession, embedding: OnnxTensor,
        samX: Float, samY: Float, origH: Int, origW: Int,
    ): Pair<Mask, Float>? {
        // Single foreground point + the SAM padding point (0,0, label −1) required when no box prompt.
        val coords = floatArrayOf(samX, samY, 0f, 0f)
        val labels = floatArrayOf(1f, -1f)
        val maskInput = FloatArray(256 * 256)                 // zeros
        val hasMask = floatArrayOf(0f)
        val origSize = floatArrayOf(origH.toFloat(), origW.toFloat())

        val tCoords = OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, 2, 2))
        val tLabels = OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, 2))
        val tMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(maskInput), longArrayOf(1, 1, 256, 256))
        val tHas = OnnxTensor.createTensor(env, FloatBuffer.wrap(hasMask), longArrayOf(1))
        val tSize = OnnxTensor.createTensor(env, FloatBuffer.wrap(origSize), longArrayOf(2))

        try {
            val inputs = mapOf(
                IMAGE_EMBEDDINGS to embedding,
                POINT_COORDS to tCoords,
                POINT_LABELS to tLabels,
                MASK_INPUT to tMask,
                HAS_MASK_INPUT to tHas,
                ORIG_IM_SIZE to tSize,
            )
            dec.run(inputs).use { res ->
                val masksT = res.get(MASKS).orElse(res.get(0)) as OnnxTensor
                val iousT = res.get(IOU_PREDICTIONS).orElse(res.get(1)) as OnnxTensor
                val shape = masksT.info.shape            // [1, K, H, W]
                val k = shape[1].toInt()
                val mh = shape[2].toInt()
                val mw = shape[3].toInt()
                val masks = masksT.floatBuffer
                val ious = FloatArray(k).also { iousT.floatBuffer.get(it) }

                // Pick the highest-IoU candidate.
                var best = 0
                for (i in 1 until k) if (ious[i] > ious[best]) best = i
                val plane = mh * mw
                val offset = best * plane
                val binary = BooleanArray(plane)
                for (i in 0 until plane) binary[i] = masks.get(offset + i) > MASK_LOGIT_THRESHOLD
                return Pair(Mask(binary, mw, mh), ious[best])
            }
        } finally {
            tCoords.close(); tLabels.close(); tMask.close(); tHas.close(); tSize.close()
        }
    }

    /**
     * Build the encoder's `1×3×1024×1024` input: resize the RGB (nearest-neighbour into the
     * top-left `newW×newH`), normalize by ImageNet mean/std, pad the rest with zeros. CHW, RGB.
     */
    private fun preprocess(rgb: IntArray, w: Int, h: Int, newW: Int, newH: Int): FloatArray {
        val out = FloatArray(3 * SAM_SIZE * SAM_SIZE)        // zero-filled = padding
        val plane = SAM_SIZE * SAM_SIZE
        val fx = w.toFloat() / newW
        val fy = h.toFloat() / newH
        for (ry in 0 until newH) {
            val sy = (ry * fy).toInt().coerceIn(0, h - 1)
            val srcRow = sy * w
            val dstRow = ry * SAM_SIZE
            for (rx in 0 until newW) {
                val sx = (rx * fx).toInt().coerceIn(0, w - 1)
                val argb = rgb[srcRow + sx]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val idx = dstRow + rx
                out[idx] = (r - MEAN_R) / STD_R
                out[plane + idx] = (g - MEAN_G) / STD_G
                out[2 * plane + idx] = (b - MEAN_B) / STD_B
            }
        }
        return out
    }

    fun close() {
        try { encoder?.close() } catch (_: Throwable) {}
        try { decoder?.close() } catch (_: Throwable) {}
        encoder = null
        decoder = null
    }

    private companion object {
        const val TAG = "SegEngine"
        const val ENCODER_ASSET = "mobile_sam_encoder.onnx"
        const val DECODER_ASSET = "mobile_sam_decoder.onnx"
        const val SAM_SIZE = 1024
        const val MASK_LOGIT_THRESHOLD = 0f

        // ImageNet normalization SAM was trained with.
        const val MEAN_R = 123.675f; const val MEAN_G = 116.28f; const val MEAN_B = 103.53f
        const val STD_R = 58.395f; const val STD_G = 57.12f; const val STD_B = 57.375f

        // I/O tensor names — must match tools/export_mobile_sam.py.
        const val INPUT_IMAGE = "input_image"
        const val IMAGE_EMBEDDINGS = "image_embeddings"
        const val POINT_COORDS = "point_coords"
        const val POINT_LABELS = "point_labels"
        const val MASK_INPUT = "mask_input"
        const val HAS_MASK_INPUT = "has_mask_input"
        const val ORIG_IM_SIZE = "orig_im_size"
        const val MASKS = "masks"
        const val IOU_PREDICTIONS = "iou_predictions"
    }
}
