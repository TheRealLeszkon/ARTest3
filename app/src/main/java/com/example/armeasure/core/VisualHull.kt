package com.example.armeasure.core

/**
 * One view's silhouette cone for the multi-view **visual hull** (Phase 3). Each captured [Keyframe]
 * extrudes its 2D [Mask] out from the camera center into world space; the object must lie inside
 * every view's cone, so intersecting the cones from all views constrains the volume before depth
 * fusion.
 *
 * A world point is "inside" this view's cone iff it projects to a pixel where [mask] is set. We reuse
 * the keyframe's [viewProjection] (world→clip) for that projection — the mask lives in camera-image
 * pixel space, reached from normalized screen via the same portrait rotate-90 mapping used for taps.
 */
class VisualHull(
    /** Column-major world→clip for this view (the keyframe's VP). */
    val viewProjection: FloatArray,
    /** The silhouette this cone is extruded from (camera-image pixel space). */
    val mask: Mask,
    /** Camera center in world space (cone apex), for reference/debug. */
    val apex: FloatArray,
)
