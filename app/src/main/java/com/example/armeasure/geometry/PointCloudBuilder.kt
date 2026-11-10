package com.example.armeasure.geometry

import android.opengl.Matrix

/**
 * Turns the segmented depth region into a **world-space** point cloud — the raw material for OBB
 * fitting + the anchor.
 *
 * For each depth pixel (u,v), invert the pinhole model to the camera frame, then transform by the
 * camera pose to WORLD (so the cloud stays put as the phone moves):
 *
 *   Z = depth_mm / 1000                      // millimeters → meters
 *   Xc = (u − cx) · Z / fx                   // right
 *   Yc = −(v − cy) · Z / fy                  // up   (image v grows downward → negate)
 *   Zc = −Z                                  // ARCore camera looks down −Z → in-front is −Z
 *   world = pose · (Xc, Yc, Zc, 1)           // pose = world_T_camera
 *
 * Critical: `fx,fy,cx,cy` are intrinsics **at depth resolution** — never RGB intrinsics on depth.
 * (Ported from ARSelectionTest `cloud/PointCloudBuilder.kt`.)
 */
object PointCloudBuilder {

    /**
     * Back-project depth pixels whose corresponding **image-pixel** falls inside [maskBinary] (the
     * SAM mask lives in camera-image space, which is higher-res than the depth image, so each depth
     * pixel is scaled into mask space).
     *
     * @param maskBinary object mask in camera-image pixel space, row-major [maskW]*[maskH].
     * @return packed world points [x0,y0,z0, …]; empty if nothing qualifies.
     */
    fun build(
        depth: ShortArray, dw: Int, dh: Int,
        fx: Float, fy: Float, cx: Float, cy: Float,
        pose: FloatArray,
        maskBinary: BooleanArray, maskW: Int, maskH: Int,
    ): FloatArray {
        if (dw == 0 || dh == 0 || maskW == 0 || maskH == 0) return FloatArray(0)
        val sx = maskW.toFloat() / dw
        val sy = maskH.toFloat() / dh
        val packed = FloatArray(dw * dh * 3)
        var n = 0
        val cam = FloatArray(4)
        val world = FloatArray(4)
        for (v in 0 until dh) {
            val iy = (v * sy).toInt()
            if (iy < 0 || iy >= maskH) continue
            val maskRow = iy * maskW
            val depthRow = v * dw
            for (u in 0 until dw) {
                val mm = depth[depthRow + u].toInt() and 0xFFFF
                if (mm == 0) continue
                val ix = (u * sx).toInt()
                if (ix < 0 || ix >= maskW) continue
                if (!maskBinary[maskRow + ix]) continue
                val z = mm / 1000f
                cam[0] = (u - cx) * z / fx
                cam[1] = -(v - cy) * z / fy
                cam[2] = -z
                cam[3] = 1f
                Matrix.multiplyMV(world, 0, pose, 0, cam, 0)
                packed[n++] = world[0]; packed[n++] = world[1]; packed[n++] = world[2]
            }
        }
        return if (n == packed.size) packed else packed.copyOf(n)
    }
}
