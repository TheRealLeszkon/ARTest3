package com.example.armeasure.geometry

import com.example.armeasure.core.MeasurementResult
import com.example.armeasure.core.Obb

/**
 * **Phase 3, step 3 — 3D Box Fitting (M5).**
 *
 * Fit a 6-faced rectangular cuboid to the fused world cloud and report L/W/H in meters:
 *   1. **Robust inliers** — statistical outlier removal ([OutlierFilter]) drops stray depth that
 *      survived hull filtering.
 *   2. **Orthogonal axes (PCA)** — [ObbFitter] gives three mutually-orthogonal principal axes; an
 *      OBB *is* a 6-faced cuboid, so the rectangular prior is enforced by construction.
 *   3. **Robust dimensions** — instead of raw min/max (which a single outlier inflates), each side
 *      length is the **2nd…98th-percentile span** of the points projected onto that axis, and the
 *      center is recentred to the middle of those spans. This is the least-squares-friendly,
 *      outlier-resistant analogue of the LM dimension refine in the brief.
 *
 * Confidence blends how many points survived outlier removal (tight, consistent surface) with having
 * enough points to trust the fit.
 */
object CuboidFitter {

    private const val MIN_POINTS = 80
    private const val TRIM = 0.02f          // 2nd…98th percentile per axis
    private const val CONF_FULL_POINTS = 1200f

    /** @param fusedWorldPoints packed [x0,y0,z0,…] from [DepthFusion]. */
    fun fit(fusedWorldPoints: FloatArray): MeasurementResult? {
        val n0 = fusedWorldPoints.size / 3
        if (n0 < MIN_POINTS) return null

        val inliers = OutlierFilter.filter(fusedWorldPoints, k = 2f)
        val nIn = inliers.size / 3
        if (nIn < MIN_POINTS) return null

        val pca = ObbFitter.fit(inliers) ?: return null
        val axes = pca.axes
        val centroid = pca.center

        // Robust per-axis span (percentile-trimmed) → extents + recentred center.
        val nAxis = nIn
        val proj = Array(3) { FloatArray(nAxis) }
        var i = 0; var j = 0
        while (i + 2 < inliers.size) {
            val dx = inliers[i] - centroid[0]; val dy = inliers[i + 1] - centroid[1]; val dz = inliers[i + 2] - centroid[2]
            for (a in 0..2) proj[a][j] = dx * axes[a][0] + dy * axes[a][1] + dz * axes[a][2]
            j++; i += 3
        }
        val half = FloatArray(3)
        val center = floatArrayOf(centroid[0], centroid[1], centroid[2])
        for (a in 0..2) {
            proj[a].sort()
            val lo = proj[a][(TRIM * nAxis).toInt().coerceIn(0, nAxis - 1)]
            val hi = proj[a][((1f - TRIM) * nAxis).toInt().coerceIn(0, nAxis - 1)]
            val mid = (lo + hi) * 0.5f
            half[a] = ((hi - lo) * 0.5f).coerceAtLeast(0.005f)
            center[0] += mid * axes[a][0]; center[1] += mid * axes[a][1]; center[2] += mid * axes[a][2]
        }

        val obb = Obb(center, axes, half)
        val dims = obb.dimensions
        val survival = nIn.toFloat() / n0                                    // how tight the cloud was
        val adequacy = (nIn / CONF_FULL_POINTS).coerceAtMost(1f)             // enough points?
        val confidence = (0.5f * survival + 0.5f * adequacy).coerceIn(0f, 1f)

        // Report L ≥ W ≥ H (descending), independent of PCA axis order.
        val sorted = dims.sortedDescending()
        return MeasurementResult(
            lengthM = sorted[0], widthM = sorted[1], heightM = sorted[2],
            obb = obb, confidence = confidence, pointCount = nIn,
        )
    }
}
