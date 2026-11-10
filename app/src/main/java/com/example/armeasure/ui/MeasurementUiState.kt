package com.example.armeasure.ui

/** Load state of the MobileSAM engine — distinguishes "still loading" from "genuinely absent". */
enum class SamStatus {
    /** The 27 MB encoder is still loading + warming up in the background (a few seconds at startup). */
    LOADING,

    /** Both models loaded; tap-to-segment is available. */
    READY,

    /** Assets absent or failed to load — run tools/export_mobile_sam.py. */
    MISSING,
}

/** Where the measurement flow is right now. */
enum class MeasurePhase {
    /** No target. The user taps an object to segment + anchor it. (Phase 1) */
    SEARCHING,

    /** Coarse OBB anchored; the wireframe is reprojected every frame. (end of Phase 1) */
    MEASURED,

    /** Capturing the 3 keyframes at ~0°/45°/90° around the box. (Phase 2) */
    CAPTURING,

    /** All 3 keyframes captured; ready for geometry fusion. (end of Phase 2 → Phase 3) */
    CAPTURED,

    /** Cuboid fitted; showing final L/W/H with interactive corner-drag calibration. (Phase 3 done) */
    RESULT,
}

/**
 * Single immutable snapshot the UI renders, owned solely by
 * [com.example.armeasure.MeasureViewModel]. Every world quantity is already in ARCore WORLD
 * coordinates (the source of truth); overlays project it to SCREEN each frame via [viewProjection].
 */
data class MeasurementUiState(
    // ---- Session / model status ----
    val trackingState: String = "—",
    val trackingFailure: String? = null,
    /** True only once loading finished successfully; gates tap-to-segment. */
    val samReady: Boolean = false,
    /** Tri-state for display: LOADING (startup) vs READY vs MISSING. */
    val samStatus: SamStatus = SamStatus.LOADING,
    val samBackend: String = "",
    /** null = still probing; true/false = ARCore Depth API availability. */
    val depthSupported: Boolean? = null,

    // ---- Per-frame camera (WORLD → clip) ----
    val hasProjection: Boolean = false,
    val viewProjection: FloatArray = FloatArray(16),
    val cameraWorld: FloatArray = FloatArray(0),

    // ---- Flow ----
    val phase: MeasurePhase = MeasurePhase.SEARCHING,
    val statusText: String = "Tap the box to measure it",
    val selectionFailed: Boolean = false,
    /** True while a tap's segmentation/fit is running (drives the "Segmenting…" state). */
    val busy: Boolean = false,

    // ---- Selection silhouette (the SAM mask, projected to screen; cleared shortly after lock) ----
    /** Normalized screen points [nx0,ny0,nx1,ny1,…] of the segmented mask, for a brief confirmation. */
    val maskPointsN: FloatArray = FloatArray(0),

    // ---- World target (MEASURED) — source of truth for the wireframe ----
    val hasTarget: Boolean = false,
    val targetWorldCenter: FloatArray = FloatArray(0),
    /** 8 WORLD OBB corners [x0,y0,z0,…] (24 floats), recomputed from the anchor each frame. */
    val targetWorldCorners: FloatArray = FloatArray(0),
    /** Box side lengths (meters) along the three OBB axes. */
    val targetExtent: FloatArray = FloatArray(0),
    val targetPointCount: Int = 0,
    val targetTracking: Boolean = false,

    // ---- Debug: last inference ----
    val lastIou: Float = 0f,
    val encodeMs: Long = 0,
    val decodeMs: Long = 0,

    // ---- Phase-2 multi-view capture ----
    /** Label of the stage being shot now, e.g. "Corner (45°)". */
    val captureStageLabel: String = "",
    /** Keyframes captured so far (0..3). */
    val capturedCount: Int = 0,
    /** Target yaw for the current stage, relative to the first view (deg): 0 / 45 / 90. */
    val captureTargetYaw: Float = 0f,
    /** Current camera yaw around the box, relative to the first view (deg). */
    val captureCurrentYaw: Float = 0f,
    /** True when the current viewpoint has enough parallax vs every captured view (safe to shoot). */
    val captureParallaxOk: Boolean = false,
    /** One-line capture guidance ("Move around to ~45°", "Too close to a shot view", …). */
    val captureHint: String = "",

    // ---- Phase-3a visual hull (M3) ----
    /** Occupied hull voxel centers in WORLD space, packed [x0,y0,z0,…], downsampled for drawing. */
    val hullPointsWorld: FloatArray = FloatArray(0),
    /** Total occupied voxels in the carved hull. */
    val hullVoxelCount: Int = 0,
    /** Tightened hull bounding-box side lengths in cm (w,h,d). */
    val hullExtentCm: FloatArray = FloatArray(0),

    // ---- Phase-3 result + calibration (M5/M6) ----
    /** True once the cuboid fit produced a measurement (phase RESULT). */
    val resultReady: Boolean = false,
    /** Fit confidence 0..1 (1 = tight, well-covered fit). */
    val measurementConfidence: Float = 0f,
    /** Corner currently grabbed for drag-calibration (0..7), or -1 when none. */
    val selectedCorner: Int = -1,
)
