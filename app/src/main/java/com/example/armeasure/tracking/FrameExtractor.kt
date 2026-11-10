package com.example.armeasure.tracking

import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/**
 * Pulls the per-frame data the measurement pipeline needs out of an ARCore [Frame], converted into
 * SDK-free primitives the ViewModel can consume. Runs on the AR render thread, so it only *copies*
 * (planes / depth); heavy work (YUV→RGB, MobileSAM) happens off-thread.
 *
 * Per the thermal rule, the camera image is acquired ONLY when [wantCamera] is set (on a tap or a
 * keyframe capture) — never every frame. Depth + pose + intrinsics are acquired while [wantDepth] is
 * set (needed to back-project the segmented mask into WORLD space at selection time).
 *
 * Coordinate systems are labelled at each field; do not mix them.
 * (Ported from ARSelectionTest `ar/FrameExtractor.kt`.)
 */
object FrameExtractor {

    private const val TAG = "FrameExtractor"
    private const val NEAR = 0.1f
    private const val FAR = 30f

    /** Log intrinsics + resolutions exactly once per process — cheap, and the #1 debugging aid. */
    private var loggedIntrinsics = false

    /** Result of reading one frame. All fields are plain values / arrays. */
    data class FrameData(
        val trackingState: String,
        /** Column-major VP = Projection · View, maps a WORLD point to clip space. */
        val viewProjection: FloatArray,
        /** Camera world position (x,y,z) = translation of world_T_camera; (0,0,0) if not tracking. */
        val cameraWorld: FloatArray,
        // ---- Camera image (only when wantCamera) — for MobileSAM ----------------
        /** Raw YUV camera image for this frame, or null when not requested/available. */
        val cameraImage: YuvImage?,
        // ---- Depth (only when wantDepth) ---------------------------------------
        /** Raw 16-bit depth (mm), row-major [depthWidth]*[depthHeight]; null unless wantDepth. */
        val rawDepth: ShortArray?,
        val depthWidth: Int,
        val depthHeight: Int,
        /** Depth-resolution intrinsics (fx,fy,cx,cy); valid only when [rawDepth] != null. */
        val depthFx: Float,
        val depthFy: Float,
        val depthCx: Float,
        val depthCy: Float,
        /** Column-major world_T_camera; valid only when [rawDepth] != null. */
        val cameraPose: FloatArray,
    )

    fun extract(frame: Frame, wantCamera: Boolean, wantDepth: Boolean): FrameData {
        val camera = frame.camera
        val state = camera.trackingState.name

        // ---- View-Projection matrix (WORLD -> clip) — ARCore hands us column-major matrices. ----
        val view = FloatArray(16)
        val proj = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, NEAR, FAR)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val cameraWorld = FloatArray(3)
        if (camera.trackingState == TrackingState.TRACKING) {
            val t = camera.pose.translation
            cameraWorld[0] = t[0]; cameraWorld[1] = t[1]; cameraWorld[2] = t[2]
        }

        // ---- Depth (needed to establish the world target at selection time) ----
        var depthW = 0
        var depthH = 0
        var rawDepth: ShortArray? = null
        var depthFx = 0f; var depthFy = 0f; var depthCx = 0f; var depthCy = 0f
        val cameraPose = FloatArray(16)
        if (wantDepth && camera.trackingState == TrackingState.TRACKING) {
            try {
                frame.acquireDepthImage16Bits().use { image ->
                    depthW = image.width
                    depthH = image.height
                    val plane = image.planes[0]
                    // DEPTH16 is native byte order (little-endian on ARM); each pixel is a uint16
                    // in *millimeters*. Rows may be padded, so honor rowStride/pixelStride.
                    val buf = plane.buffer.order(ByteOrder.nativeOrder())
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val shorts = ShortArray(depthW * depthH)
                    var o = 0
                    for (yy in 0 until depthH) {
                        val rowBase = yy * rowStride
                        for (xx in 0 until depthW) {
                            shorts[o++] = buf.getShort(rowBase + xx * pixelStride)
                        }
                    }
                    // Depth-resolution intrinsics: scale the CPU-image intrinsics down to the depth
                    // resolution (depth has its OWN intrinsics — never use RGB fx/cx on it directly).
                    val ci = camera.imageIntrinsics
                    val dim = ci.imageDimensions
                    val fl = ci.focalLength
                    val pp = ci.principalPoint
                    rawDepth = shorts
                    val sx = depthW.toFloat() / dim[0]
                    val sy = depthH.toFloat() / dim[1]
                    depthFx = fl[0] * sx; depthFy = fl[1] * sy
                    depthCx = pp[0] * sx; depthCy = pp[1] * sy
                    camera.pose.toMatrix(cameraPose, 0)   // world_T_camera

                    if (!loggedIntrinsics) {
                        Log.i(
                            TAG,
                            "Depth image ${depthW}x$depthH | RGB image ${dim[0]}x${dim[1]} " +
                                "fx=${fl[0]} fy=${fl[1]} cx=${pp[0]} cy=${pp[1]} " +
                                "(depth intrinsics = these scaled to depth res)"
                        )
                        loggedIntrinsics = true
                    }
                }
            } catch (e: NotYetAvailableException) {
                // Depth for this exact frame isn't ready yet — next frame will have it.
            }
        }

        // ---- Camera image (MobileSAM) — copy planes out, close the native Image immediately. ----
        var cameraImage: YuvImage? = null
        if (wantCamera && camera.trackingState == TrackingState.TRACKING) {
            try {
                frame.acquireCameraImage().use { image ->
                    cameraImage = YuvImage.from(image)
                }
            } catch (e: NotYetAvailableException) {
                // CPU image not ready this frame; try again next frame.
            }
        }

        return FrameData(
            trackingState = state,
            viewProjection = vp,
            cameraWorld = cameraWorld,
            cameraImage = cameraImage,
            rawDepth = rawDepth,
            depthWidth = depthW,
            depthHeight = depthH,
            depthFx = depthFx,
            depthFy = depthFy,
            depthCx = depthCx,
            depthCy = depthCy,
            cameraPose = cameraPose,
        )
    }
}
