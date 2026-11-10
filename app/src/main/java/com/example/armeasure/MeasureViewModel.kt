package com.example.armeasure

import android.app.Application
import android.opengl.Matrix
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.armeasure.core.CameraIntrinsics
import com.example.armeasure.core.CameraPose
import com.example.armeasure.core.CaptureStage
import com.example.armeasure.core.CaptureState
import com.example.armeasure.core.DepthMap
import com.example.armeasure.core.Keyframe
import com.example.armeasure.core.KeyframeCache
import com.example.armeasure.core.Mask
import com.example.armeasure.core.MeasurementResult
import com.example.armeasure.core.Obb
import com.example.armeasure.core.ViewProjection
import com.example.armeasure.geometry.CuboidFitter
import com.example.armeasure.geometry.DepthFusion
import com.example.armeasure.geometry.MultiViewGeometryPipeline
import com.example.armeasure.geometry.VisualHullIntersector
import com.example.armeasure.segment.SegmentationInferenceEngine
import com.example.armeasure.tracking.ARTrackingManager
import com.example.armeasure.tracking.AnchoredObb
import com.example.armeasure.tracking.FrameExtractor
import com.example.armeasure.tracking.YuvImage
import com.example.armeasure.ui.MeasurePhase
import com.example.armeasure.ui.MeasurementUiState
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2

/**
 * The only place that talks to ARCore + the segmentation engine. Owns [MeasurementUiState].
 *
 * **Phase 1 — interactive 3D anchoring** (brief Step 1): tap → MobileSAM mask →
 * [MultiViewGeometryPipeline.coarseObb] back-projects masked depth to WORLD → PCA coarse OBB →
 * ARCore **World Anchor** ([ARTrackingManager.anchor]) → live wireframe reprojection.
 *
 * **Phase 2 — multi-view capture** (brief Step 2): once anchored, the front view is auto-recorded as
 * the first keyframe; the user then walks to ~45° and ~90° and presses the shutter. Each capture
 * re-segments the box (SAM point-prompt at the anchor center projected into that view) and records a
 * [Keyframe] (RGB, pose Tᵢ, intrinsics Kᵢ, mask, depth) into a thread-safe [KeyframeCache]. An
 * **insufficient-parallax guard** rejects a shot too close in angle to an already-captured view.
 *
 * Threading: MobileSAM encode/decode + the fit run on [Dispatchers.Default]; only the ARCore anchor
 * creation touches the session, on the render loop ([onFrame]).
 */
class MeasureViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(
        MeasurementUiState(statusText = "Loading segmentation model…")
    )
    val uiState: StateFlow<MeasurementUiState> = _uiState.asStateFlow()

    private var engine: SegmentationInferenceEngine? = null

    /** Pending tap in normalized screen coords [nx,ny]; consumed on the render loop. */
    private val pendingTap = AtomicReference<FloatArray?>(null)
    /** A pending shutter press (Phase 2). */
    private val pendingShutter = AtomicBoolean(false)
    /** Single-flight guard shared by segmentation + capture (both use the SAM engine). */
    private val samBusy = AtomicBoolean(false)
    /** A completed Phase-1 coarse fit awaiting anchoring on the render loop. */
    private val pendingResult = AtomicReference<PendingResult?>(null)

    /** The world-space target. Touched only on the render loop. */
    private var target: AnchoredObb? = null

    // ---- Phase-2 capture state (touched on render loop + capture job) ----
    private val keyframes = KeyframeCache()
    @Volatile private var captureState = CaptureState.initial()
    /** Yaw (deg) of the first (front) view around the box — the reference for relative angles. */
    @Volatile private var firstViewYaw = 0f
    /** Coarse-OBB corners (world) captured at anchor time — the seed region for the M3 hull carve. */
    @Volatile private var seedCorners: FloatArray? = null

    // ---- Phase-3 result + calibration (M5/M6) ----
    /** A completed cuboid fit awaiting re-anchoring on the render loop. */
    private val pendingMeasure = AtomicReference<MeasurementResult?>(null)
    /** Editable cuboid (RESULT phase): mutable half-extents + fixed PCA axes; center via the anchor. */
    @Volatile private var editAxes: Array<FloatArray>? = null
    @Volatile private var editHalf: FloatArray? = null
    /** Corner grabbed for drag-calibration (0..7), or -1. */
    @Volatile private var selectedCorner = -1

    private class PendingResult(
        val obb: Obb, val pointCount: Int, val iou: Float,
        val encodeMs: Long, val decodeMs: Long, val maskPointsN: FloatArray,
        /** The front view, recorded as the first keyframe once anchored. */
        val frontKeyframe: Keyframe,
    )

    init {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val e = SegmentationInferenceEngine(getApplication())
                engine = e
                _uiState.update {
                    it.copy(
                        samReady = e.ready,
                        samStatus = if (e.ready) com.example.armeasure.ui.SamStatus.READY
                        else com.example.armeasure.ui.SamStatus.MISSING,
                        samBackend = e.backend,
                        statusText = if (e.ready) "Tap the box to measure it"
                        else "SAM model missing — run tools/export_mobile_sam.py",
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Segmentation engine init failed", t)
                _uiState.update {
                    it.copy(
                        samStatus = com.example.armeasure.ui.SamStatus.MISSING,
                        statusText = "SAM model failed to load — see logcat",
                    )
                }
            }
        }
    }

    fun setDepthSupported(supported: Boolean) = _uiState.update { it.copy(depthSupported = supported) }
    fun setTrackingFailure(reason: String?) = _uiState.update { it.copy(trackingFailure = reason) }

    /** A tap on the AR view (normalized screen coords 0..1). Queued; resolved on the render loop. */
    fun onTap(nx: Float, ny: Float) {
        if (_uiState.value.phase != MeasurePhase.SEARCHING) return
        if (samBusy.get()) return
        pendingTap.set(floatArrayOf(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f)))
        hapticTick()
        _uiState.update { it.copy(selectionFailed = false, statusText = "Segmenting…") }
    }

    /** Shutter press during Phase-2 capture. Queued; resolved on the render loop. */
    fun onShutter() {
        if (_uiState.value.phase != MeasurePhase.CAPTURING) return
        if (samBusy.get()) return
        pendingShutter.set(true)
        hapticTick()
        _uiState.update { it.copy(statusText = "Capturing ${captureState.currentStage.label}…") }
    }

    /** Clear everything and return to SEARCHING (detaches the anchor, drops keyframes). */
    fun clearMeasurement() {
        target?.detach()
        target = null
        pendingTap.set(null)
        pendingShutter.set(false)
        pendingResult.set(null)
        pendingMeasure.set(null)
        keyframes.clear()
        captureState = CaptureState.initial()
        _uiState.update {
            it.copy(
                phase = MeasurePhase.SEARCHING,
                statusText = "Tap the box to measure it",
                selectionFailed = false, busy = false,
                hasTarget = false, maskPointsN = FloatArray(0),
                targetWorldCenter = FloatArray(0), targetWorldCorners = FloatArray(0),
                targetExtent = FloatArray(0), targetPointCount = 0, targetTracking = false,
                captureStageLabel = "", capturedCount = 0, captureTargetYaw = 0f,
                captureCurrentYaw = 0f, captureParallaxOk = false, captureHint = "",
                hullPointsWorld = FloatArray(0), hullVoxelCount = 0, hullExtentCm = FloatArray(0),
                resultReady = false, measurementConfidence = 0f, selectedCorner = -1,
            )
        }
        seedCorners = null
        editAxes = null
        editHalf = null
        selectedCorner = -1
        Log.i(TAG, "MEASUREMENT_CLEARED")
    }

    /** Called every frame (~30 fps) from the AR render loop. */
    fun onFrame(session: Session, frame: Frame) {
        val cur = _uiState.value
        val searching = cur.phase == MeasurePhase.SEARCHING
        val capturing = cur.phase == MeasurePhase.CAPTURING
        val tapPending = pendingTap.get() != null
        val shutterPending = pendingShutter.get()
        val wantCamera = ((tapPending && searching) || (shutterPending && capturing)) &&
            cur.samReady && !samBusy.get()
        val wantDepth = (searching || capturing) && cur.depthSupported == true
        val data = FrameExtractor.extract(frame, wantCamera, wantDepth)
        val img = data.cameraImage

        // ---- Phase 1: dispatch a segmentation+fit job. ----
        if (tapPending && searching && img != null && data.rawDepth != null && cur.samReady) {
            val tap = pendingTap.getAndSet(null)
            if (tap != null && samBusy.compareAndSet(false, true)) {
                val depth = data.rawDepth
                val vp = data.viewProjection.copyOf()
                val pose = data.cameraPose.copyOf()
                _uiState.update { it.copy(busy = true) }
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        segmentAndFit(tap, img, depth, data.depthWidth, data.depthHeight,
                            data.depthFx, data.depthFy, data.depthCx, data.depthCy, pose, vp)
                    } catch (t: Throwable) {
                        Log.e(TAG, "segment/fit failed", t)
                        _uiState.update { it.copy(busy = false, selectionFailed = true,
                            statusText = "Segmentation failed — try again") }
                    } finally {
                        samBusy.set(false)
                    }
                }
            }
        }

        // ---- Phase 2: dispatch a keyframe capture job. ----
        if (shutterPending && capturing && img != null && data.rawDepth != null && cur.samReady) {
            val t = target
            if (t != null && samBusy.compareAndSet(false, true)) {
                pendingShutter.set(false)
                val depth = data.rawDepth
                val vp = data.viewProjection.copyOf()
                val pose = data.cameraPose.copyOf()
                val center = t.worldCenter()
                _uiState.update { it.copy(busy = true) }
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        captureKeyframe(img, depth, data.depthWidth, data.depthHeight,
                            data.depthFx, data.depthFy, data.depthCx, data.depthCy, pose, vp, center)
                    } catch (t2: Throwable) {
                        Log.e(TAG, "capture failed", t2)
                        _uiState.update { it.copy(busy = false, captureHint = "Capture failed — try again") }
                    } finally {
                        samBusy.set(false)
                    }
                }
            }
        }

        // ---- Anchor a completed Phase-1 fit on the render loop (ARCore session call). ----
        val pr = pendingResult.getAndSet(null)
        if (pr != null && searching) {
            target?.detach()
            target = ARTrackingManager.anchor(session, pr.obb, pr.pointCount)
            lockTarget(pr)
        }

        // ---- Re-anchor a completed cuboid fit (M5) at the refined center → RESULT phase. ----
        val pm = pendingMeasure.getAndSet(null)
        if (pm != null) {
            target?.detach()
            target = ARTrackingManager.anchor(session, pm.obb, pm.pointCount)
            editAxes = Array(3) { pm.obb.axes[it].copyOf() }
            editHalf = pm.obb.halfExtents.copyOf()
            _uiState.update {
                it.copy(
                    phase = MeasurePhase.RESULT, busy = false,
                    resultReady = true,
                    measurementConfidence = pm.confidence,
                    targetExtent = pm.obb.dimensions,
                    targetPointCount = pm.pointCount,
                    hullPointsWorld = FloatArray(0),   // the refined cuboid replaces the hull cloud
                    statusText = "Measured — drag a corner to fine-tune",
                )
            }
        }

        // ---- Per-frame world→screen for the wireframe, from the live anchor pose. ----
        val t = target
        var center = cur.targetWorldCenter
        var corners = cur.targetWorldCorners
        var tracking = cur.targetTracking
        val ea = editAxes; val eh = editHalf
        if (t != null) {
            center = t.worldCenter(); tracking = t.tracking
            corners = if (_uiState.value.resultReady && ea != null && eh != null)
                cornersFrom(center, ea, eh)   // editable cuboid (RESULT) — mutable half-extents
            else t.worldCorners()             // frozen coarse OBB (capture phases)
        }

        // ---- Phase-2 live guidance: current yaw around the box + parallax check. ----
        var curYaw = cur.captureCurrentYaw
        var parallaxOk = cur.captureParallaxOk
        var hint = cur.captureHint
        if (capturing && t != null && data.cameraWorld.size >= 3 && center.size >= 3) {
            val absYaw = yawDeg(data.cameraWorld[0], data.cameraWorld[2], center[0], center[2])
            curYaw = normalize180(absYaw - firstViewYaw)
            parallaxOk = hasParallax(absYaw, center)
            hint = if (parallaxOk) "Ready — tap to capture"
            else "Move to a new side of the box"
        }

        val liveExtent = if (_uiState.value.resultReady && eh != null)
            floatArrayOf(eh[0] * 2f, eh[1] * 2f, eh[2] * 2f) else cur.targetExtent

        _uiState.update {
            it.copy(
                trackingState = data.trackingState,
                hasProjection = true,
                viewProjection = data.viewProjection,
                cameraWorld = data.cameraWorld,
                targetWorldCenter = center,
                targetWorldCorners = corners,
                targetExtent = liveExtent,
                targetTracking = tracking,
                captureCurrentYaw = curYaw,
                captureParallaxOk = parallaxOk,
                captureHint = if (capturing) hint else it.captureHint,
            )
        }
    }

    // ---- M6: corner-drag calibration ------------------------------------------------------------

    /** Pick the corner nearest a touch (screen px), if within [CORNER_PICK_PX]. */
    fun onResultPick(x: Float, y: Float, screenW: Float, screenH: Float) {
        if (_uiState.value.phase != MeasurePhase.RESULT) return
        val corners = _uiState.value.targetWorldCorners
        val vp = _uiState.value.viewProjection
        if (corners.size < 24) return
        var best = -1; var bestD2 = CORNER_PICK_PX * CORNER_PICK_PX
        for (c in 0 until 8) {
            val s = projScreen(vp, corners[c * 3], corners[c * 3 + 1], corners[c * 3 + 2], screenW, screenH) ?: continue
            val dx = s[0] - x; val dy = s[1] - y; val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { bestD2 = d2; best = c }
        }
        selectedCorner = best
        _uiState.update { it.copy(selectedCorner = best) }
    }

    /**
     * Drag the grabbed corner: project the screen drag onto each OBB axis's screen direction, pick
     * the best-aligned axis, and resize that dimension symmetrically about the center. "Drag outward
     * = grow, inward = shrink," regardless of which corner you grabbed.
     */
    fun onResultDrag(dragX: Float, dragY: Float, screenW: Float, screenH: Float) {
        val c = selectedCorner
        val axes = editAxes; val half = editHalf
        if (c < 0 || axes == null || half == null) return
        val center = _uiState.value.targetWorldCenter
        val vp = _uiState.value.viewProjection
        if (center.size < 3) return
        val sc = projScreen(vp, center[0], center[1], center[2], screenW, screenH) ?: return

        var bestA = -1; var bestAlign = 0f; var bestGain = 0f
        val eps = 0.05f
        for (a in 0..2) {
            val sign = if ((c shr a) and 1 != 0) 1f else -1f     // this corner's side along axis a
            // Screen displacement of moving OUTWARD along axis a by eps meters.
            val se = projScreen(vp,
                center[0] + sign * eps * axes[a][0],
                center[1] + sign * eps * axes[a][1],
                center[2] + sign * eps * axes[a][2], screenW, screenH) ?: continue
            val ox = se[0] - sc[0]; val oy = se[1] - sc[1]
            val len = kotlin.math.sqrt(ox * ox + oy * oy)
            if (len < 1e-3f) continue
            val align = (dragX * ox + dragY * oy) / len          // px of drag along outward dir
            if (abs(align) > abs(bestAlign)) {
                bestAlign = align; bestA = a; bestGain = eps / len // meters per screen px along a
            }
        }
        if (bestA < 0) return
        val deltaM = bestAlign * bestGain                         // world meters (signed: out=+)
        half[bestA] = (half[bestA] + deltaM).coerceIn(0.01f, 2f)  // symmetric about center
    }

    fun onResultDragEnd() {
        if (selectedCorner >= 0) {
            val h = editHalf
            if (h != null) Log.i(TAG, "CORNER_ADJUST L×W×H=${"%.1f".format(h[0]*200)}×" +
                "${"%.1f".format(h[1]*200)}×${"%.1f".format(h[2]*200)}cm")
        }
        selectedCorner = -1
        _uiState.update { it.copy(selectedCorner = -1) }
    }

    /** 8 world corners of an OBB (center + fixed axes + half-extents), [Obb.EDGES] bit layout. */
    private fun cornersFrom(center: FloatArray, axes: Array<FloatArray>, half: FloatArray): FloatArray {
        val out = FloatArray(24)
        var o = 0
        for (i in 0 until 8) {
            val sx = if (i and 1 != 0) 1f else -1f
            val sy = if (i and 2 != 0) 1f else -1f
            val sz = if (i and 4 != 0) 1f else -1f
            for (k in 0..2) out[o++] = center[k] +
                sx * half[0] * axes[0][k] + sy * half[1] * axes[1][k] + sz * half[2] * axes[2][k]
        }
        return out
    }

    /** Project a WORLD point to screen pixels (or null if behind the camera). */
    private fun projScreen(vp: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float): FloatArray? {
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(x, y, z, 1f), 0)
        if (clip[3] <= 0f) return null
        return floatArrayOf(
            (clip[0] / clip[3] * 0.5f + 0.5f) * w,
            (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * h,
        )
    }

    /** Background (Phase 1): MobileSAM → coarse OBB. Stores a [PendingResult] for the render loop. */
    private fun segmentAndFit(
        tap: FloatArray, img: YuvImage, depth: ShortArray,
        dw: Int, dh: Int, fx: Float, fy: Float, cx: Float, cy: Float, pose: FloatArray, vp: FloatArray,
    ) {
        val eng = engine ?: return
        val rgb = img.toArgb()
        val iw = img.width; val ih = img.height
        // Tap (portrait normalized screen) → camera-image pixel (landscape sensor): imgX depends on
        // ny, imgY on (1−nx) — the portrait rotate-90 mapping proven in the sibling PoCs.
        val imgX = tap[1] * iw
        val imgY = (1f - tap[0]) * ih

        val seg = eng.segment(rgb, iw, ih, imgX, imgY)
        if (seg == null) {
            _uiState.update { it.copy(busy = false, selectionFailed = true,
                statusText = "Couldn't segment there — tap the object") }
            return
        }
        val fit = MultiViewGeometryPipeline.coarseObb(depth, dw, dh, fx, fy, cx, cy, pose, seg.mask)
        if (fit == null) {
            _uiState.update { it.copy(busy = false, selectionFailed = true,
                statusText = "Not enough surface — move closer and tap again") }
            Log.i(TAG, "FIT_FAIL maskPx=${seg.mask.count()} (need ≥${MultiViewGeometryPipeline.MIN_POINTS} depth pts)")
            return
        }
        val front = buildKeyframe(CaptureStage.ANGLE_0, rgb, iw, ih, seg.mask, depth, dw, dh, fx, fy, cx, cy, pose, vp)
        pendingResult.set(PendingResult(
            fit.obb, fit.pointCount, seg.iou, seg.encodeMs, seg.decodeMs, maskToScreenPoints(seg.mask), front))
        _uiState.update { it.copy(statusText = "Anchoring…") }
        Log.i(TAG, "SEG+FIT iou=${"%.2f".format(seg.iou)} maskPx=${seg.mask.count()} " +
            "pts=${fit.pointCount} enc=${seg.encodeMs}ms dec=${seg.decodeMs}ms")
    }

    /**
     * Background (Phase 2): re-segment the box from this viewpoint (SAM prompt = the anchor center
     * projected into the frame), enforce parallax, and record a [Keyframe].
     */
    private fun captureKeyframe(
        img: YuvImage, depth: ShortArray, dw: Int, dh: Int,
        fx: Float, fy: Float, cx: Float, cy: Float, pose: FloatArray, vp: FloatArray, center: FloatArray,
    ) {
        val eng = engine ?: return
        val iw = img.width; val ih = img.height

        // Insufficient-parallax guard: reject a shot too close in angle to any captured view.
        val camPos = floatArrayOf(pose[12], pose[13], pose[14])
        val absYaw = yawDeg(camPos[0], camPos[2], center[0], center[2])
        if (!hasParallax(absYaw, center)) {
            _uiState.update { it.copy(busy = false,
                captureHint = "Too close to a captured view — move further around") }
            Log.i(TAG, "CAPTURE_REJECT insufficient parallax at yaw=${"%.0f".format(absYaw)}°")
            return
        }

        // SAM prompt = anchor center projected into this view.
        val p = projectWorldToImage(vp, center, iw, ih)
        if (p == null) {
            _uiState.update { it.copy(busy = false, captureHint = "Aim so the box is on screen, then shoot") }
            return
        }
        val rgb = img.toArgb()
        val seg = eng.segment(rgb, iw, ih, p[0], p[1])
        if (seg == null || seg.mask.count() < MIN_MASK_PX) {
            _uiState.update { it.copy(busy = false, captureHint = "Couldn't segment the box here — re-aim") }
            return
        }

        val stage = captureState.currentStage
        keyframes.add(buildKeyframe(stage, rgb, iw, ih, seg.mask, depth, dw, dh, fx, fy, cx, cy, pose, vp))
        captureState = captureState.advance()
        val count = keyframes.size()
        val complete = captureState.isComplete && count >= CaptureStage.entries.size
        Log.i(TAG, "KEYFRAME_CAPTURED stage=$stage yaw=${"%.0f".format(normalize180(absYaw - firstViewYaw))}° " +
            "maskPx=${seg.mask.count()} total=$count")

        _uiState.update {
            it.copy(
                busy = !complete,   // stay busy through the hull carve if this was the last view
                phase = if (complete) MeasurePhase.CAPTURED else MeasurePhase.CAPTURING,
                capturedCount = count,
                captureStageLabel = captureState.currentStage.label,
                captureTargetYaw = captureState.currentStage.targetYawDeg,
                captureHint = if (complete) "" else "Move to ~${captureState.currentStage.targetYawDeg.toInt()}°",
                statusText = if (complete) "Carving visual hull…"
                else "Captured $count/3 — move to ${captureState.currentStage.label}",
            )
        }

        // ---- Phase 3a (M3): all views in → carve the multi-view visual hull. ----
        if (complete) carveVisualHull()
    }

    /** Background (Phase 3a): intersect the per-view silhouette cones into a carved world hull. */
    private fun carveVisualHull() {
        val corners = seedCorners
        if (corners == null) { _uiState.update { it.copy(busy = false) }; return }
        val (bmin, bmax) = expandedAabb(corners, expand = 0.45f, margin = 0.08f)
        val hull = MultiViewGeometryPipeline.carveHull(keyframes.all(), bmin, bmax)
        if (hull == null) {
            _uiState.update { it.copy(busy = false,
                statusText = "Visual hull empty — recapture with cleaner, wider angles") }
            Log.i(TAG, "HULL_EMPTY views=${keyframes.size()}")
            return
        }
        val ext = floatArrayOf(
            (hull.boundsMax[0] - hull.boundsMin[0]) * 100f,
            (hull.boundsMax[1] - hull.boundsMin[1]) * 100f,
            (hull.boundsMax[2] - hull.boundsMin[2]) * 100f,
        )
        Log.i(TAG, "HULL_CARVED voxels=${hull.count} voxel=${"%.1f".format(hull.voxel * 100)}cm " +
            "extent=${"%.0f".format(ext[0])}×${"%.0f".format(ext[1])}×${"%.0f".format(ext[2])}cm")
        _uiState.update {
            it.copy(
                hullPointsWorld = downsamplePoints(hull.points, MAX_HULL_DRAW_POINTS),
                hullVoxelCount = hull.count,
                hullExtentCm = ext,
                statusText = "Fusing depth + fitting box…",
            )
        }
        measureFromHull(hull)
    }

    /** Background (Phase 3b+3c): hull-filtered depth fusion (M4) → cuboid fit (M5). */
    private fun measureFromHull(hull: VisualHullIntersector.Hull) {
        val fused = DepthFusion.fuse(keyframes.all(), hull)
        Log.i(TAG, "DEPTH_FUSED points=${fused.size / 3} (hull-filtered)")
        val result = CuboidFitter.fit(fused)
        if (result == null) {
            _uiState.update { it.copy(busy = false,
                statusText = "Not enough box surface to measure — recapture closer/cleaner") }
            Log.i(TAG, "FIT_FAIL fused=${fused.size / 3}")
            return
        }
        val d = result.obb.dimensions
        Log.i(TAG, "CUBOID_FIT L×W×H=${"%.1f".format(result.lengthM * 100)}×" +
            "${"%.1f".format(result.widthM * 100)}×${"%.1f".format(result.heightM * 100)}cm " +
            "conf=${"%.2f".format(result.confidence)} pts=${result.pointCount} " +
            "obb=${"%.0f".format(d[0]*100)}/${"%.0f".format(d[1]*100)}/${"%.0f".format(d[2]*100)}")
        // Hand off to the render loop to re-anchor at the refined center (ARCore session call there).
        pendingMeasure.set(result)
        _uiState.update { it.copy(statusText = "Measured — drag a corner to fine-tune") }
    }

    /** Anchor established: record the front view as keyframe 0 and enter Phase-2 capture. */
    private fun lockTarget(pr: PendingResult) {
        keyframes.clear()
        keyframes.add(pr.frontKeyframe)
        seedCorners = pr.obb.corners()   // seed region for the M3 hull carve (world, frozen at anchor)
        // The front view defines the 0° reference; advance past ANGLE_0.
        val center = target?.worldCenter() ?: pr.obb.center
        firstViewYaw = yawDeg(pr.frontKeyframe.pose.translation()[0], pr.frontKeyframe.pose.translation()[2],
            center[0], center[2])
        captureState = CaptureState.initial().advance()   // ANGLE_0 done → ANGLE_45 next
        _uiState.update {
            it.copy(
                phase = MeasurePhase.CAPTURING,
                statusText = "View 1/3 captured — move to ${captureState.currentStage.label}",
                selectionFailed = false, busy = false,
                hasTarget = true,
                targetExtent = pr.obb.dimensions,
                targetPointCount = pr.pointCount,
                maskPointsN = pr.maskPointsN,
                lastIou = pr.iou, encodeMs = pr.encodeMs, decodeMs = pr.decodeMs,
                capturedCount = 1,
                captureStageLabel = captureState.currentStage.label,
                captureTargetYaw = captureState.currentStage.targetYawDeg,
                captureHint = "Move to ~${captureState.currentStage.targetYawDeg.toInt()}°",
            )
        }
        viewModelScope.launch { delay(1500); _uiState.update { it.copy(maskPointsN = FloatArray(0)) } }
    }

    /** Assemble a [Keyframe]: RGB intrinsics derived from the depth-res ones (× resolution ratio). */
    private fun buildKeyframe(
        stage: CaptureStage, rgb: IntArray, iw: Int, ih: Int, mask: Mask, depth: ShortArray,
        dw: Int, dh: Int, fx: Float, fy: Float, cx: Float, cy: Float, pose: FloatArray, vp: FloatArray,
    ): Keyframe {
        val sxR = iw.toFloat() / dw; val syR = ih.toFloat() / dh
        val rgbIntr = CameraIntrinsics(fx * sxR, fy * syR, cx * sxR, cy * syR, iw, ih)
        val depthIntr = CameraIntrinsics(fx, fy, cx, cy, dw, dh)
        return Keyframe(
            stage = stage,
            rgb = rgb, rgbWidth = iw, rgbHeight = ih,
            pose = CameraPose(pose),
            intrinsics = rgbIntr,
            mask = mask,
            depth = DepthMap(depth, dw, dh, depthIntr),
            viewProjection = ViewProjection(vp),
            timestampMs = System.currentTimeMillis(),
        )
    }

    /** True if [absYaw] differs from every captured view's yaw (around [center]) by ≥ [MIN_PARALLAX_DEG]. */
    private fun hasParallax(absYaw: Float, center: FloatArray): Boolean =
        keyframes.all().all { kf ->
            val t = kf.pose.translation()
            angDiff(absYaw, yawDeg(t[0], t[2], center[0], center[2])) >= MIN_PARALLAX_DEG
        }

    /** Yaw (deg) of a camera position around a center in the horizontal (XZ) plane. */
    private fun yawDeg(camX: Float, camZ: Float, cX: Float, cZ: Float): Float =
        Math.toDegrees(atan2((camX - cX).toDouble(), (camZ - cZ).toDouble())).toFloat()

    /** Smallest absolute angular difference (deg) between two yaws, 0..180. */
    private fun angDiff(a: Float, b: Float): Float {
        var d = abs(normalize180(a - b)); if (d > 180f) d = 360f - d; return d
    }

    /** Wrap an angle to (−180, 180]. */
    private fun normalize180(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Project a WORLD point to camera-image pixel space (or null if behind/off camera). */
    private fun projectWorldToImage(vp: FloatArray, w: FloatArray, iw: Int, ih: Int): FloatArray? {
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(w[0], w[1], w[2], 1f), 0)
        if (clip[3] <= 0f) return null
        val nxs = clip[0] / clip[3] * 0.5f + 0.5f            // normalized screen x (right)
        val nys = 1f - (clip[1] / clip[3] * 0.5f + 0.5f)      // normalized screen y (down)
        if (nxs < 0f || nxs > 1f || nys < 0f || nys > 1f) return null
        return floatArrayOf(nys * iw, (1f - nxs) * ih)        // → image pixel (same mapping as taps)
    }

    /** World AABB of packed corner points, grown by [expand] of its size + a fixed [margin] (meters). */
    private fun expandedAabb(corners: FloatArray, expand: Float, margin: Float): Pair<FloatArray, FloatArray> {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < corners.size) {
            val x = corners[i]; val y = corners[i + 1]; val z = corners[i + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        val ex = (maxX - minX) * expand + margin
        val ey = (maxY - minY) * expand + margin
        val ez = (maxZ - minZ) * expand + margin
        return Pair(
            floatArrayOf(minX - ex, minY - ey, minZ - ez),
            floatArrayOf(maxX + ex, maxY + ey, maxZ + ez),
        )
    }

    /** Uniformly subsample a packed point cloud down to ≈[max] points (for cheap overlay drawing). */
    private fun downsamplePoints(packed: FloatArray, max: Int): FloatArray {
        val n = packed.size / 3
        if (n <= max) return packed
        val stride = (n + max - 1) / max
        val out = FloatArray((n / stride + 1) * 3)
        var o = 0; var i = 0
        while (i < n) {
            out[o++] = packed[i * 3]; out[o++] = packed[i * 3 + 1]; out[o++] = packed[i * 3 + 2]
            i += stride
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    /** Downsample the segmented [mask] to normalized screen points for a brief on-tap confirmation. */
    private fun maskToScreenPoints(mask: Mask): FloatArray {
        val w = mask.width; val h = mask.height
        if (w == 0 || h == 0) return FloatArray(0)
        val count = mask.count()
        if (count == 0) return FloatArray(0)
        var stride = 1
        while (count / (stride * stride) > MAX_MASK_POINTS) stride++
        val out = ArrayList<Float>(2 * (count / (stride * stride) + 1))
        var y = 0
        while (y < h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (mask.binary[row + x]) {
                    out.add(1f - y.toFloat() / h)   // nx
                    out.add(x.toFloat() / w)         // ny
                }
                x += stride
            }
            y += stride
        }
        return out.toFloatArray()
    }

    private fun hapticTick() {
        try {
            val ctx = getApplication<Application>()
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            }
            vib?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            // Haptics are a nicety; never let them break selection.
        }
    }

    override fun onCleared() {
        target?.detach()
        target = null
        keyframes.clear()
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "MeasureVM"
        const val MAX_MASK_POINTS = 2500
        /** A captured view must be at least this many degrees from every prior view (parallax). */
        const val MIN_PARALLAX_DEG = 20f
        /** Minimum SAM mask pixels for a capture to be worth keeping. */
        const val MIN_MASK_PX = 200
        /** Cap on hull voxel centers streamed to the overlay (drawing cost). */
        const val MAX_HULL_DRAW_POINTS = 3000
        /** Touch-pick radius (px) for grabbing a corner handle in calibration. */
        const val CORNER_PICK_PX = 70f
    }
}
