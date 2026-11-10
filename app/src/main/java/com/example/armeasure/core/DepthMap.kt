package com.example.armeasure.core

/**
 * A hardware/AR depth map: uint16 **millimeters** (stored in a [ShortArray], read as unsigned),
 * row-major `width*height`, `0` = no depth. The value is a **Z-depth** (perpendicular to the camera
 * plane along the optical axis), not straight-line range. Back-project through [intrinsics], which
 * are the RGB intrinsics scaled to this (lower) depth resolution.
 */
class DepthMap(
    val millimeters: ShortArray,
    val width: Int,
    val height: Int,
    val intrinsics: CameraIntrinsics,
) {
    /** Unsigned mm at (u,v), or 0 if out of range / invalid. */
    fun mmAt(u: Int, v: Int): Int {
        if (u < 0 || u >= width || v < 0 || v >= height) return 0
        return millimeters[v * width + u].toInt() and 0xFFFF
    }
}
