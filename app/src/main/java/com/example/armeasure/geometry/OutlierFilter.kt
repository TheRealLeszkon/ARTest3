package com.example.armeasure.geometry

import kotlin.math.sqrt

/**
 * Drops stray points before the OBB fit. Depth bleeds a little onto the floor/wall behind the
 * object, and those far-flung points would stretch a PCA axis and inflate the box. We remove points
 * whose distance from the centroid exceeds mean + k·σ (statistical outlier removal).
 *
 * (Ported from ARTest2 `measure/OutlierFilter.kt`.)
 */
object OutlierFilter {

    /**
     * @param points packed world points [x0,y0,z0,…].
     * @param k      how many standard deviations from the mean distance to keep (default 2).
     * @return a new packed array with outliers removed (or the input if too few points).
     */
    fun filter(points: FloatArray, k: Float = 2f): FloatArray {
        val n = points.size / 3
        if (n < 8) return points

        var cx = 0f; var cy = 0f; var cz = 0f
        var i = 0
        while (i < points.size) { cx += points[i]; cy += points[i + 1]; cz += points[i + 2]; i += 3 }
        cx /= n; cy /= n; cz /= n

        val dist = FloatArray(n)
        var mean = 0f
        i = 0
        var j = 0
        while (i < points.size) {
            val dx = points[i] - cx; val dy = points[i + 1] - cy; val dz = points[i + 2] - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            dist[j++] = d
            mean += d
            i += 3
        }
        mean /= n
        var varSum = 0f
        for (d in dist) { val e = d - mean; varSum += e * e }
        val std = sqrt(varSum / n)
        val limit = mean + k * std

        val out = FloatArray(points.size)
        var o = 0
        i = 0; j = 0
        while (i < points.size) {
            if (dist[j] <= limit) {
                out[o++] = points[i]; out[o++] = points[i + 1]; out[o++] = points[i + 2]
            }
            i += 3; j++
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
