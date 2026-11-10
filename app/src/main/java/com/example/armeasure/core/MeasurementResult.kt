package com.example.armeasure.core

/**
 * The final fused measurement of a box, produced by the multi-view geometry pipeline (Phase 3).
 * Dimensions are sorted descending so [lengthM] ≥ [widthM] ≥ [heightM] is NOT assumed — instead
 * they map to the fitted [obb]'s three axes (see [Obb.dimensions]). Distances in **meters**.
 */
data class MeasurementResult(
    val lengthM: Float,
    val widthM: Float,
    val heightM: Float,
    /** The oriented box the dimensions came from. */
    val obb: Obb,
    /** 0..1 confidence from the fit residual / view coverage (1 = tight fit, many views). */
    val confidence: Float,
    /** How many fused world points the fit used (debug). */
    val pointCount: Int,
) {
    /** Dimensions in centimeters, in axis order (w, h, d). */
    fun dimensionsCm(): FloatArray = floatArrayOf(lengthM * 100f, widthM * 100f, heightM * 100f)
}
