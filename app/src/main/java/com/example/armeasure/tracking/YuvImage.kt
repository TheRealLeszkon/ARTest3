package com.example.armeasure.tracking

import android.media.Image

/**
 * SDK-free snapshot of one ARCore camera frame in YUV_420_888. We copy the raw planes on the AR
 * render thread (a cheap memcpy) and close the native [Image] immediately — holding it would starve
 * ARCore's image pool and freeze the session. The expensive YUV→RGB + segmentation then run on a
 * background thread (see `segment/`).
 *
 * (Ported from ARSelectionTest `ar/YuvImage.kt`.)
 */
class YuvImage(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val uRowStride: Int,
    val vRowStride: Int,
    val uPixelStride: Int,
    val vPixelStride: Int,
) {
    companion object {
        /** Copies planes out of an ARCore YUV_420_888 [Image]. Caller still owns/closes the Image. */
        fun from(image: Image): YuvImage {
            val p = image.planes
            val yb = p[0].buffer
            val ub = p[1].buffer
            val vb = p[2].buffer
            val y = ByteArray(yb.remaining()).also { yb.get(it) }
            val u = ByteArray(ub.remaining()).also { ub.get(it) }
            val v = ByteArray(vb.remaining()).also { vb.get(it) }
            return YuvImage(
                width = image.width,
                height = image.height,
                y = y, u = u, v = v,
                yRowStride = p[0].rowStride,
                uRowStride = p[1].rowStride,
                vRowStride = p[2].rowStride,
                uPixelStride = p[1].pixelStride,
                vPixelStride = p[2].pixelStride,
            )
        }
    }

    /**
     * BT.601 full-range YUV_420_888 → packed opaque ARGB (row-major `width*height`). This is the RGB
     * the MobileSAM encoder consumes. Done here (not in [from]) so it stays off the render thread.
     */
    fun toArgb(): IntArray {
        val out = IntArray(width * height)
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val uvRow = (row shr 1)
            for (col in 0 until width) {
                val yy = (y[yRow + col].toInt() and 0xFF)
                val uvCol = (col shr 1)
                val uIdx = uvRow * uRowStride + uvCol * uPixelStride
                val vIdx = uvRow * vRowStride + uvCol * vPixelStride
                val uu = (u[uIdx].toInt() and 0xFF) - 128
                val vv = (v[vIdx].toInt() and 0xFF) - 128
                var r = (yy + 1.402f * vv).toInt()
                var g = (yy - 0.344136f * uu - 0.714136f * vv).toInt()
                var b = (yy + 1.772f * uu).toInt()
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
