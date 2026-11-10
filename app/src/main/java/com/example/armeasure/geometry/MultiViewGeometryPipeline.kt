package com.example.armeasure.geometry

import com.example.armeasure.core.Keyframe
import com.example.armeasure.core.Mask
import com.example.armeasure.core.MeasurementResult
import com.example.armeasure.core.Obb

/**
 * **Module 3 of the brief.** Native geometry algorithms for the measurement pipeline. Two entry
 * points, one per phase that needs geometry:
 *
 * - [coarseObb] — **Phase 1.** From a single tap's mask + this frame's depth/pose, back-project a
 *   world cloud, strip outliers, and PCA-fit a coarse oriented box to anchor. Fully implemented
 *   (reuses [PointCloudBuilder] → [OutlierFilter] → [ObbFitter]).
 *
 * - [measure] — **Phase 3.** From the 3 captured keyframes, run visual-hull intersection → depth
 *   fusion → cuboid fit into a final [MeasurementResult]. Orchestration is wired; the three steps
 *   are stubs ([VisualHullIntersector], [DepthFusion], [CuboidFitter]) landing in M3–M5.
 */
object MultiViewGeometryPipeline {

    /** Phase-1 coarse fit. @return the OBB + inlier point count, or null if too few valid points. */
    fun coarseObb(
        depth: ShortArray, dw: Int, dh: Int,
        fx: Float, fy: Float, cx: Float, cy: Float,
        pose: FloatArray,
        mask: Mask,
    ): Result? {
        val cloud = PointCloudBuilder.build(depth, dw, dh, fx, fy, cx, cy, pose, mask.binary, mask.width, mask.height)
        if (cloud.size / 3 < MIN_POINTS) return null
        val inliers = OutlierFilter.filter(cloud)
        val obb = ObbFitter.fit(inliers) ?: return null
        return Result(obb, inliers.size / 3)
    }

    /**
     * **Phase 3, step 1 (M3).** Carve the multi-view visual hull from the captured keyframes over a
     * world seed region (the coarse OBB, expanded). @return the hull, or null if the views don't
     * overlap. Pure geometry — safe to run off the render thread.
     */
    fun carveHull(
        keyframes: List<Keyframe>,
        boundsMin: FloatArray,
        boundsMax: FloatArray,
    ): VisualHullIntersector.Hull? {
        if (keyframes.size < 2) return null
        val hulls = keyframes.map { kf ->
            com.example.armeasure.core.VisualHull(
                viewProjection = kf.viewProjection.matrix,
                mask = kf.mask,
                apex = kf.pose.translation(),
            )
        }
        return VisualHullIntersector.intersect(hulls, boundsMin, boundsMax)
    }

    /**
     * Phase-3 fused measurement (M3–M5). Carve the visual hull, fuse hull-filtered depth, then fit a
     * 6-faced cuboid. Throws from the M4/M5 stubs until those milestones land.
     */
    fun measure(keyframes: List<Keyframe>, boundsMin: FloatArray, boundsMax: FloatArray): MeasurementResult? {
        val hull = carveHull(keyframes, boundsMin, boundsMax) ?: return null
        val fused = DepthFusion.fuse(keyframes, hull)
        return CuboidFitter.fit(fused)
    }

    /** Coarse-fit output: the box + how many inlier points it came from. */
    class Result(val obb: Obb, val pointCount: Int)

    /** Minimum masked depth points to trust the back-projection enough to anchor. */
    const val MIN_POINTS = 60
}
