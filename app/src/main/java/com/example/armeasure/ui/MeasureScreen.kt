package com.example.armeasure.ui

import android.graphics.Paint
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.armeasure.MeasureViewModel
import com.example.armeasure.core.Obb
import com.example.armeasure.tracking.ARTrackingManager
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView

/**
 * **Module 4 of the brief — MeasurementUIOverlay.** The whole screen:
 *   - live camera via SceneView's [ARScene] (owns the ARCore session),
 *   - a **tap** layer that triggers interactive segmentation + anchoring,
 *   - the segmented **silhouette** (brief on-tap confirmation),
 *   - the **world-anchored OBB wireframe** + corner handles + dimensional callout, reprojected from
 *     the live camera pose every frame so they stay glued to the box as the phone moves,
 *   - a **capture guidance ring** (Phase-2 stub), a status banner, a debug HUD, and Clear.
 */
@Composable
fun MeasureScreen(
    viewModel: MeasureViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    Box(modifier = modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            collisionSystem = collisionSystem,
            childNodes = childNodes,
            cameraNode = cameraNode,
            planeRenderer = true,
            sessionConfiguration = { session, config ->
                viewModel.setDepthSupported(ARTrackingManager.configure(session, config))
            },
            onTrackingFailureChanged = { reason -> viewModel.setTrackingFailure(reason?.name) },
            onSessionUpdated = { session, frame -> viewModel.onFrame(session, frame) },
        )

        // Tap layer: above the camera, below the HUD. Canvas overlays don't consume touch, so this
        // is the only pointer handler.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o -> viewModel.onTap(o.x / size.width, o.y / size.height) }
                }
        )

        // Overlays (bottom to top).
        MaskSilhouetteOverlay(uiState, Modifier.fillMaxSize())   // transient on-tap confirmation
        HullOverlay(uiState, Modifier.fillMaxSize())             // WORLD visual-hull voxel cloud (M3)
        WireframeOverlay(uiState, Modifier.fillMaxSize())        // WORLD OBB + corners + callout

        Banner(uiState, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        Hud(uiState, Modifier.align(Alignment.TopStart).padding(12.dp))

        // Phase-2 capture guidance: step dots (top) + plain instruction + a readiness-gated shutter.
        if (uiState.phase == MeasurePhase.CAPTURING || uiState.phase == MeasurePhase.CAPTURED) {
            CaptureStepDots(uiState, Modifier.align(Alignment.TopCenter).padding(top = 62.dp))
        }
        if (uiState.phase == MeasurePhase.CAPTURING) {
            CaptureInstruction(uiState, Modifier.align(Alignment.BottomCenter).padding(bottom = 122.dp))
            ShutterButton(
                enabled = !uiState.busy && uiState.captureParallaxOk,
                highlighted = uiState.captureParallaxOk,
                onClick = viewModel::onShutter,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            )
        }

        // Phase-3 calibration: drag a corner handle to fine-tune the box dimensions.
        if (uiState.phase == MeasurePhase.RESULT) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { o ->
                                viewModel.onResultPick(o.x, o.y, size.width.toFloat(), size.height.toFloat())
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                viewModel.onResultDrag(drag.x, drag.y, size.width.toFloat(), size.height.toFloat())
                            },
                            onDragEnd = { viewModel.onResultDragEnd() },
                            onDragCancel = { viewModel.onResultDragEnd() },
                        )
                    },
            )
            ResultInfo(uiState, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
        }

        // Clear is available once we have a target (capture / result).
        if (uiState.hasTarget) {
            Button(
                onClick = viewModel::clearMeasurement,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Text("Clear")
            }
        }
    }
}

/** Big circular shutter for Phase-2 capture. Ring turns green when the current view has parallax. */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring = when { !enabled -> Color(0x66FFFFFF); highlighted -> HighlightColor; else -> Color.White }
    Box(
        modifier = modifier
            .size(78.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.5f
            drawCircle(ring, radius = r, style = Stroke(width = 6f))
            drawCircle(ring, radius = r * 0.72f)
        }
    }
}

/** The SAM mask, downsampled to screen points — a brief confirmation that the tap segmented right. */
@Composable
private fun MaskSilhouetteOverlay(state: MeasurementUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val pts = state.maskPointsN
        if (pts.size < 2) return@Canvas
        var i = 0
        while (i + 1 < pts.size) {
            val px = pts[i] * size.width
            val py = pts[i + 1] * size.height
            drawRect(MaskColor, topLeft = Offset(px - 2f, py - 2f), size = Size(4f, 4f))
            i += 2
        }
    }
}

/**
 * The carved **visual hull** (M3): occupied voxel centers in WORLD space, reprojected through this
 * frame's view-projection each frame (so they stay locked to the box as the phone moves). This is the
 * visual confirmation that the 3 silhouette cones intersected into the object's volume.
 */
@Composable
private fun HullOverlay(state: MeasurementUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val pts = state.hullPointsWorld
        if (pts.size < 3 || !state.hasProjection) return@Canvas
        val vp = state.viewProjection
        val w = size.width; val h = size.height
        val p = FloatArray(4); val clip = FloatArray(4)
        var i = 0
        while (i + 2 < pts.size) {
            p[0] = pts[i]; p[1] = pts[i + 1]; p[2] = pts[i + 2]; p[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw > 0f) {
                val px = (clip[0] / cw * 0.5f + 0.5f) * w
                val py = (1f - (clip[1] / cw * 0.5f + 0.5f)) * h
                drawRect(HullColor, topLeft = Offset(px - 2f, py - 2f), size = Size(4f, 4f))
            }
            i += 3
        }
    }
}

/**
 * The world-anchored OBB: 8 WORLD corners projected to screen through THIS frame's view-projection,
 * connected as a 12-edge wireframe, with small corner handles (display-only; interactive drag lands
 * in M6) and a dimensional callout near the center. Corners are world-space (from the live anchor
 * pose), so the box stays on the physical object as the phone orbits.
 */
@Composable
private fun WireframeOverlay(state: MeasurementUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (!state.hasTarget || !state.hasProjection || state.targetWorldCorners.size < 24) return@Canvas
        val w = size.width; val h = size.height
        val vp = state.viewProjection
        val p = FloatArray(4); val clip = FloatArray(4)
        val sx = FloatArray(8); val sy = FloatArray(8); val ok = BooleanArray(8)
        for (c in 0 until 8) {
            p[0] = state.targetWorldCorners[c * 3]
            p[1] = state.targetWorldCorners[c * 3 + 1]
            p[2] = state.targetWorldCorners[c * 3 + 2]
            p[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw <= 0f) { ok[c] = false; continue }
            sx[c] = (clip[0] / cw * 0.5f + 0.5f) * w
            sy[c] = (1f - (clip[1] / cw * 0.5f + 0.5f)) * h
            ok[c] = true
        }
        val color = if (state.targetTracking) HighlightColor else MarkerLostColor
        for (e in Obb.EDGES) {
            val a = e[0]; val b = e[1]
            if (ok[a] && ok[b]) drawLine(color, Offset(sx[a], sy[a]), Offset(sx[b], sy[b]), strokeWidth = 6f)
        }
        // Corner handles — draggable in RESULT (M6). The grabbed one is filled + larger.
        for (c in 0 until 8) if (ok[c]) {
            if (c == state.selectedCorner) {
                drawCircle(color, radius = 16f, center = Offset(sx[c], sy[c]))
            } else {
                drawCircle(color, radius = 11f, center = Offset(sx[c], sy[c]), style = Stroke(width = 3f))
            }
        }

        // Dimensional callout near the projected center.
        val cen = state.targetWorldCenter
        if (cen.size >= 3 && state.targetExtent.size >= 3) {
            Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(cen[0], cen[1], cen[2], 1f), 0)
            if (clip[3] > 0f) {
                val px = (clip[0] / clip[3] * 0.5f + 0.5f) * w
                val py = (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * h
                val e = state.targetExtent
                val label = "%.0f × %.0f × %.0f cm".format(e[0] * 100, e[1] * 100, e[2] * 100)
                drawContext.canvas.nativeCanvas.apply {
                    drawText(label, px + 14f, py - 14f, calloutBg)
                    drawText(label, px + 14f, py - 14f, calloutFg)
                }
            }
        }
    }
}

/**
 * Phase-2 progress: three dots (filled per captured view) + "Photo N of 3". A plain, legible
 * replacement for the old compass dial — no angle math, just how many shots are done.
 */
@Composable
private fun CaptureStepDots(state: MeasurementUiState, modifier: Modifier) {
    val total = 3
    val done = state.capturedCount.coerceIn(0, total)
    val current = (done + 1).coerceAtMost(total)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until total) {
            Canvas(modifier = Modifier.size(12.dp)) {
                val r = size.minDimension * 0.5f
                if (i < done) drawCircle(HighlightColor, radius = r)
                else drawCircle(Color.White, radius = r, style = Stroke(width = 3f))
            }
            if (i < total - 1) Box(Modifier.size(width = 8.dp, height = 1.dp))
        }
        Text(
            text = if (state.phase == MeasurePhase.CAPTURED) "  All 3 photos captured"
            else "  Photo $current of $total",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

/**
 * The single plain-language capture prompt shown just above the shutter: e.g. "Move to a new side of
 * the box", turning green "Ready — tap to capture" when the current viewpoint has enough parallax
 * ([MeasurementUiState].captureParallaxOk). Text comes straight from `captureHint`.
 */
@Composable
private fun CaptureInstruction(state: MeasurementUiState, modifier: Modifier) {
    if (state.captureHint.isEmpty()) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = state.captureHint,
            color = if (state.captureParallaxOk) HighlightColor else Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

/** Phase-3 result readout: final L×W×H (cm) + fit confidence + the calibration hint. */
@Composable
private fun ResultInfo(state: MeasurementUiState, modifier: Modifier) {
    if (state.targetExtent.size < 3) return
    val d = state.targetExtent.toList().sortedDescending()   // L ≥ W ≥ H
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "%.0f × %.0f × %.0f cm".format(d[0] * 100, d[1] * 100, d[2] * 100),
            color = HighlightColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Text(
            text = "confidence ${(state.measurementConfidence * 100).toInt()}%  ·  drag a corner to adjust",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun Banner(state: MeasurementUiState, modifier: Modifier) {
    val color = when {
        state.selectionFailed -> MarkerLostColor
        state.hasTarget -> HighlightColor
        else -> Color.White
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB0000000))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = state.statusText,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun Hud(state: MeasurementUiState, modifier: Modifier) {
    val depthText = when (state.depthSupported) {
        true -> "supported"; false -> "NOT supported"; null -> "checking..."
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xAA000000))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        HudLine("Phase", state.phase.name)
        HudLine("Tracking", state.trackingState)
        HudLine("Depth API", depthText)
        HudLine("SAM", when (state.samStatus) {
            SamStatus.LOADING -> "loading model…"
            SamStatus.READY -> "ready · ${state.samBackend}"
            SamStatus.MISSING -> "missing (run export)"
        })
        if (state.busy) HudLine("", "segmenting…")
        if (state.hasTarget) {
            if (state.targetExtent.size >= 3) {
                val e = state.targetExtent
                HudLine("Box", "%.0f×%.0f×%.0f cm (%d pts)".format(
                    e[0] * 100, e[1] * 100, e[2] * 100, state.targetPointCount))
            }
            HudLine("IoU", "%.2f".format(state.lastIou))
            HudLine("Infer", "enc ${state.encodeMs}ms · dec ${state.decodeMs}ms")
            HudLine("Anchor", if (state.targetTracking) "TRACKING" else "paused")
        }
        if (state.phase == MeasurePhase.CAPTURING || state.phase == MeasurePhase.CAPTURED) {
            HudLine("Views", "${state.capturedCount}/3" +
                if (state.phase == MeasurePhase.CAPTURING) " · next ${state.captureStageLabel}" else "")
            if (state.phase == MeasurePhase.CAPTURING) {
                HudLine("Yaw", "%.0f° → %.0f° · %s".format(
                    state.captureCurrentYaw, state.captureTargetYaw,
                    if (state.captureParallaxOk) "OK" else "move"))
            }
        }
        if (state.hullVoxelCount > 0 && state.hullExtentCm.size >= 3) {
            val e = state.hullExtentCm
            HudLine("Hull", "${state.hullVoxelCount} vox · %.0f×%.0f×%.0f cm".format(e[0], e[1], e[2]))
        }
        if (state.resultReady) {
            HudLine("Fit", "conf ${(state.measurementConfidence * 100).toInt()}% · ${state.targetPointCount} pts")
        }
        state.trackingFailure?.let { HudLine("Lost tracking", it) }
    }
}

@Composable
private fun HudLine(label: String, value: String) {
    Text(
        text = if (label.isEmpty()) value else "$label: $value",
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

private val HighlightColor = Color(0xFF00E676)   // world OBB (tracking): green
private val MarkerLostColor = Color(0xFFFF5252)  // OBB (anchor paused) + failure banner: red
private val MaskColor = Color(0x8000E5FF)        // SAM silhouette confirmation: translucent cyan
private val HullColor = Color(0xC0FF9800)        // carved visual-hull voxels: translucent orange

private val calloutFg = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 40f
    isAntiAlias = true
    typeface = android.graphics.Typeface.MONOSPACE
}
private val calloutBg = Paint().apply {
    color = android.graphics.Color.BLACK
    textSize = 40f
    isAntiAlias = true
    typeface = android.graphics.Typeface.MONOSPACE
    style = Paint.Style.STROKE
    strokeWidth = 5f
}
