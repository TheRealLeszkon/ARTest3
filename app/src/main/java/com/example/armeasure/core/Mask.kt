package com.example.armeasure.core

/**
 * Binary instance mask in **camera-image** pixel space (landscape sensor orientation, same as the
 * depth image), `true` = object pixel, row-major `width*height`. Produced by the segmentation
 * engine from a tap prompt; consumed by the point-cloud back-projection.
 */
class Mask(val binary: BooleanArray, val width: Int, val height: Int) {
    /** Count of object pixels. */
    fun count(): Int = binary.count { it }
}

/** Colorized mask ready to overlay: ARGB, row-major, in image-pixel space. */
class MaskOverlay(val colors: IntArray, val width: Int, val height: Int)
