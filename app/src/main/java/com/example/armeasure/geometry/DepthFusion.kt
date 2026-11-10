package com.example.armeasure.geometry

import com.example.armeasure.core.Keyframe

/**
 * **Phase 3, step 2 — Depth Fusion (M4).**
 *
 * Back-project each keyframe's hardware depth into WORLD space (through its mask, reusing
 * [PointCloudBuilder]) and keep only points that fall inside the visual [VisualHullIntersector.Hull].
 * The per-view mask already restricts to the object silhouette; the hull additionally removes
 * background/floor/neighbour depth that leaks past a single mask, leaving a clean multi-view cloud of
 * just the box surface for [CuboidFitter].
 *
 * Textureless-surface note (spec §3): where depth is sparse inside the hull (plain cardboard), the
 * hull still bounds the volume, so the cuboid fit degrades gracefully rather than failing; a
 * Canny/Sobel edge fallback can be layered here later without changing this contract.
 */
object DepthFusion {

    /** @return packed world points [x0,y0,z0,…] fused across all keyframes, hull-filtered. */
    fun fuse(keyframes: List<Keyframe>, hull: VisualHullIntersector.Hull): FloatArray {
        val out = ArrayList<Float>(4096)
        for (kf in keyframes) {
            val d = kf.depth
            val cloud = PointCloudBuilder.build(
                d.millimeters, d.width, d.height,
                d.intrinsics.fx, d.intrinsics.fy, d.intrinsics.cx, d.intrinsics.cy,
                kf.pose.worldTCamera,
                kf.mask.binary, kf.mask.width, kf.mask.height,
            )
            var i = 0
            while (i + 2 < cloud.size) {
                val x = cloud[i]; val y = cloud[i + 1]; val z = cloud[i + 2]
                if (hull.contains(x, y, z)) { out.add(x); out.add(y); out.add(z) }
                i += 3
            }
        }
        return out.toFloatArray()
    }
}
