package com.example.armeasure.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The three capture viewpoints of the multi-view system (Phase 2). The user is guided to shoot the
 * box at roughly these yaw offsets relative to the first view, giving enough parallax for the
 * visual-hull intersection + depth fusion to constrain the volume.
 */
enum class CaptureStage(val targetYawDeg: Float, val label: String) {
    ANGLE_0(0f, "Front (0°)"),
    ANGLE_45(45f, "Corner (45°)"),
    ANGLE_90(90f, "Side (90°)");

    /** Next stage in the capture sequence, or null when this is the last. */
    fun next(): CaptureStage? = entries.getOrNull(ordinal + 1)
}

/**
 * One captured keyframe: everything the geometry pipeline needs from a single shutter press —
 * the RGB frame, the camera pose ([CameraPose] Tᵢ) and intrinsics ([CameraIntrinsics] Kᵢ), the SAM
 * segmentation [Mask], and the hardware [DepthMap]. Immutable snapshot; stored in [KeyframeCache].
 *
 * @param rgb packed ARGB, row-major, in camera-image pixel space (same orientation as [mask]/[depth]).
 */
class Keyframe(
    val stage: CaptureStage,
    val rgb: IntArray,
    val rgbWidth: Int,
    val rgbHeight: Int,
    val pose: CameraPose,
    val intrinsics: CameraIntrinsics,
    val mask: Mask,
    val depth: DepthMap,
    val viewProjection: ViewProjection,
    val timestampMs: Long,
)

/**
 * Thread-safe in-memory cache of captured keyframes (Phase 2). The capture happens on the AR render
 * loop while the fusion runs on a background thread, so reads/writes cross threads —
 * [CopyOnWriteArrayList] keeps that safe without locking the render loop.
 *
 * **Phase-1 status: unused.** Wired in M2 (capture state machine).
 */
class KeyframeCache {
    private val frames = CopyOnWriteArrayList<Keyframe>()

    fun add(frame: Keyframe) { frames.add(frame) }
    fun all(): List<Keyframe> = frames.toList()
    fun forStage(stage: CaptureStage): Keyframe? = frames.lastOrNull { it.stage == stage }
    fun size(): Int = frames.size
    fun clear() = frames.clear()
}

/**
 * The Phase-2 capture state machine. Drives the guidance UI: which angle to shoot next, how many are
 * done, whether we have enough parallax to fuse. **Phase-1 status: typed stub** — transitions are
 * defined but not yet wired to the shutter (M2).
 */
class CaptureState private constructor(
    val currentStage: CaptureStage,
    val completed: Set<CaptureStage>,
) {
    val isComplete: Boolean get() = completed.size == CaptureStage.entries.size

    /** Record a successful capture at [currentStage] and advance to the next pending stage. */
    fun advance(): CaptureState {
        val done = completed + currentStage
        val next = currentStage.next()?.takeIf { it !in done } ?: currentStage
        return CaptureState(next, done)
    }

    companion object {
        fun initial() = CaptureState(CaptureStage.ANGLE_0, emptySet())
    }
}
