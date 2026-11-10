package com.example.armeasure.tracking

import android.util.Log
import com.example.armeasure.core.Obb
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * A world-space **oriented** box carried by an ARCore [Anchor] — the source of truth for the
 * highlight. The box center is NOT a frozen world coordinate: it's held by an anchor that ARCore
 * continuously drift-corrects (loop closure, IMU/visual fusion), so the box stays glued to the
 * physical object over a walk-around. The 8 corners are stored as fixed **world-frame** offsets from
 * the center (captured at fit time) and recomputed each frame as `anchorCenter + offset`.
 *
 * (Adapts ARSelectionTest `world/TargetWorldObject.kt` from an AABB to the PCA [Obb].)
 */
class AnchoredObb private constructor(
    val anchor: Anchor,
    /** 8 corner offsets from center, world-frame: [x0,y0,z0, …] (24 floats), in [Obb.EDGES] order. */
    private val cornerOffsets: FloatArray,
    /** Box side lengths in meters (the three OBB extents), debug + callout. */
    val extent: FloatArray,
    /** Number of masked depth points the box was fit from (debug). */
    val pointCount: Int,
) {
    /** True while ARCore is actively tracking the anchor (its pose is trustworthy this frame). */
    val tracking: Boolean get() = anchor.trackingState == TrackingState.TRACKING

    /** Current WORLD center (x,y,z) — the anchor's live, drift-corrected translation. */
    fun worldCenter(): FloatArray = anchor.pose.translation

    /** The 8 box corners in WORLD coordinates this frame: `anchorCenter + fixedOffset` (24 floats). */
    fun worldCorners(): FloatArray {
        val c = anchor.pose.translation
        val out = FloatArray(24)
        var i = 0
        while (i < 24) {
            out[i] = c[0] + cornerOffsets[i]
            out[i + 1] = c[1] + cornerOffsets[i + 1]
            out[i + 2] = c[2] + cornerOffsets[i + 2]
            i += 3
        }
        return out
    }

    /** Release the ARCore anchor when the selection is cleared. */
    fun detach() {
        try {
            anchor.detach()
        } catch (t: Throwable) {
            Log.w(TAG, "anchor detach failed", t)
        }
    }

    companion object {
        private const val TAG = "AnchoredObb"

        /**
         * Anchor the fitted [obb]: create an ARCore anchor at its center and freeze the 8 corner
         * offsets in the world frame. Must be called on the AR render thread (ARCore session call).
         */
        fun create(session: Session, obb: Obb, pointCount: Int): AnchoredObb {
            val center = obb.center
            val corners = obb.corners()
            val offsets = FloatArray(24)
            var i = 0
            while (i < 24) {
                offsets[i] = corners[i] - center[0]
                offsets[i + 1] = corners[i + 1] - center[1]
                offsets[i + 2] = corners[i + 2] - center[2]
                i += 3
            }
            val anchor = session.createAnchor(
                Pose(floatArrayOf(center[0], center[1], center[2]), floatArrayOf(0f, 0f, 0f, 1f))
            )
            val e = obb.dimensions
            Log.i(
                TAG,
                "OBB_ANCHORED points=$pointCount " +
                    "center=(${"%.2f".format(center[0])},${"%.2f".format(center[1])},${"%.2f".format(center[2])}) " +
                    "extent=(${"%.2f".format(e[0])},${"%.2f".format(e[1])},${"%.2f".format(e[2])})m"
            )
            return AnchoredObb(anchor, offsets, e, pointCount)
        }
    }
}
