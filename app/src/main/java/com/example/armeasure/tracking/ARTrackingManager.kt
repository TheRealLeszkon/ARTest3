package com.example.armeasure.tracking

import com.example.armeasure.core.Obb
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * **Module 1 of the brief.** The single facade for ARKit/ARCore session concerns: session
 * configuration (Depth API), visual-inertial-odometry frame reads, and World Anchor creation. VIO
 * tracking itself is handled natively by ARCore/SceneView; between ML inferences we rely purely on
 * it (thermal rule), reprojecting the anchored box each frame rather than re-segmenting.
 *
 * Frame reads are delegated to [FrameExtractor]; anchor lifetime to [AnchoredObb]. This object holds
 * the session-level policy so both the UI (`sessionConfiguration`) and the ViewModel share one place.
 */
object ARTrackingManager {

    /**
     * Apply the measurement session policy. Called from `ARScene { sessionConfiguration = … }`.
     * @return whether the ARCore Depth API is supported (drives the "depth unavailable" UI path).
     */
    fun configure(session: Session, config: Config): Boolean {
        val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.depthMode =
            if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.focusMode = Config.FocusMode.AUTO
        return depthSupported
    }

    /** Create a drift-corrected world anchor around a fitted oriented box (render-thread only). */
    fun anchor(session: Session, obb: Obb, pointCount: Int): AnchoredObb =
        AnchoredObb.create(session, obb, pointCount)
}
