package com.example.armeasure.core

/**
 * Pinhole intrinsics for one image, in **that image's** pixel space. The depth image is lower-res
 * than the RGB frame and has its OWN intrinsics — never mix them (see [DepthMap]).
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
)

/**
 * A camera pose as the column-major 4×4 `world_T_camera` matrix (ARCore/OpenGL convention:
 * `Camera.pose.toMatrix`). Multiplying a camera-frame homogeneous point by this yields a WORLD point.
 */
@JvmInline
value class CameraPose(val worldTCamera: FloatArray) {
    /** Camera world position = translation column of the matrix. */
    fun translation(): FloatArray = floatArrayOf(worldTCamera[12], worldTCamera[13], worldTCamera[14])
}

/**
 * The per-frame view-projection matrix (column-major, `Projection · View`) that maps a WORLD point
 * to clip space. Overlays reproject world geometry to the screen through this every frame.
 */
@JvmInline
value class ViewProjection(val matrix: FloatArray)
