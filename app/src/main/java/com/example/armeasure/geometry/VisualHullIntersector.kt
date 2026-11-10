package com.example.armeasure.geometry

import android.opengl.Matrix
import com.example.armeasure.core.VisualHull

/**
 * **Phase 3, step 1 — Multi-View Visual Hull (M3).**
 *
 * Each captured view extrudes its 2D silhouette [VisualHull] into a world-space cone from the camera
 * center. The object lies inside every view's cone, so the **intersection** of the cones bounds the
 * object volume — a strong, depth-independent constraint that also tells depth fusion (M4) which
 * points are background.
 *
 * Implementation: voxel-carve a world grid over a seed region (the coarse OBB, expanded). A voxel is
 * kept iff its center projects **inside the mask of every view** (via each view's `viewProjection` →
 * normalized screen → image pixel, the same mapping taps use). Mask membership is tested with a
 * 1-pixel tolerance so silhouette-edge/pose noise doesn't punch holes in the carve.
 */
object VisualHullIntersector {

    /** Result of intersecting the per-view cones: an occupancy grid + world bounds + voxel centers. */
    class Hull(
        /** World min corner of the (tightened) occupied region. */
        val boundsMin: FloatArray,
        /** World max corner. */
        val boundsMax: FloatArray,
        val voxel: Float,
        val nx: Int, val ny: Int, val nz: Int,
        /** Grid min corner the occupancy is indexed from. */
        private val gridMin: FloatArray,
        /** Occupancy, row-major x→y→z, length nx*ny*nz. */
        private val occupancy: BooleanArray,
        /** Occupied voxel centers, packed [x0,y0,z0,…] (world) — feeds M4/M5 + the overlay. */
        val points: FloatArray,
    ) {
        val count: Int get() = points.size / 3

        /** True if a world point falls in an occupied voxel. */
        fun contains(x: Float, y: Float, z: Float): Boolean {
            val ix = ((x - gridMin[0]) / voxel).toInt()
            val iy = ((y - gridMin[1]) / voxel).toInt()
            val iz = ((z - gridMin[2]) / voxel).toInt()
            if (ix < 0 || ix >= nx || iy < 0 || iy >= ny || iz < 0 || iz >= nz) return false
            return occupancy[(iz * ny + iy) * nx + ix]
        }
    }

    /** Cap on total voxels — the grid coarsens automatically to stay under this. */
    private const val MAX_VOXELS = 2_000_000

    /**
     * @param hulls     per-view silhouette cones (need ≥ 2).
     * @param boundsMin,boundsMax  world seed region to carve (e.g. the coarse OBB, expanded).
     * @param voxel     starting voxel edge in meters (coarsened if the grid would exceed the cap).
     * @return the carved hull, or null if too few views / empty intersection.
     */
    fun intersect(
        hulls: List<VisualHull>,
        boundsMin: FloatArray,
        boundsMax: FloatArray,
        voxel: Float = 0.01f,
    ): Hull? {
        if (hulls.size < 2) return null

        // Coarsen the voxel until the grid fits under MAX_VOXELS.
        var vx = voxel
        var nx: Int; var ny: Int; var nz: Int
        while (true) {
            nx = (((boundsMax[0] - boundsMin[0]) / vx).toInt() + 1).coerceAtLeast(1)
            ny = (((boundsMax[1] - boundsMin[1]) / vx).toInt() + 1).coerceAtLeast(1)
            nz = (((boundsMax[2] - boundsMin[2]) / vx).toInt() + 1).coerceAtLeast(1)
            if (nx.toLong() * ny * nz <= MAX_VOXELS) break
            vx *= 1.5f
        }

        val occupancy = BooleanArray(nx * ny * nz)
        val pts = ArrayList<Float>(1024)
        var occMinX = Float.MAX_VALUE; var occMinY = Float.MAX_VALUE; var occMinZ = Float.MAX_VALUE
        var occMaxX = -Float.MAX_VALUE; var occMaxY = -Float.MAX_VALUE; var occMaxZ = -Float.MAX_VALUE

        val clip = FloatArray(4)
        val p = FloatArray(4)
        for (iz in 0 until nz) {
            val wz = boundsMin[2] + (iz + 0.5f) * vx
            for (iy in 0 until ny) {
                val wy = boundsMin[1] + (iy + 0.5f) * vx
                for (ix in 0 until nx) {
                    val wx = boundsMin[0] + (ix + 0.5f) * vx
                    if (!insideAll(hulls, wx, wy, wz, p, clip)) continue
                    occupancy[(iz * ny + iy) * nx + ix] = true
                    pts.add(wx); pts.add(wy); pts.add(wz)
                    if (wx < occMinX) occMinX = wx; if (wx > occMaxX) occMaxX = wx
                    if (wy < occMinY) occMinY = wy; if (wy > occMaxY) occMaxY = wy
                    if (wz < occMinZ) occMinZ = wz; if (wz > occMaxZ) occMaxZ = wz
                }
            }
        }
        if (pts.isEmpty()) return null

        return Hull(
            boundsMin = floatArrayOf(occMinX, occMinY, occMinZ),
            boundsMax = floatArrayOf(occMaxX, occMaxY, occMaxZ),
            voxel = vx, nx = nx, ny = ny, nz = nz,
            gridMin = floatArrayOf(boundsMin[0], boundsMin[1], boundsMin[2]),
            occupancy = occupancy,
            points = pts.toFloatArray(),
        )
    }

    private fun insideAll(hulls: List<VisualHull>, x: Float, y: Float, z: Float, p: FloatArray, clip: FloatArray): Boolean {
        for (h in hulls) if (!insideOne(h, x, y, z, p, clip)) return false
        return true
    }

    /** True if the world point projects into [h]'s mask (1-px tolerance). */
    private fun insideOne(h: VisualHull, x: Float, y: Float, z: Float, p: FloatArray, clip: FloatArray): Boolean {
        p[0] = x; p[1] = y; p[2] = z; p[3] = 1f
        Matrix.multiplyMV(clip, 0, h.viewProjection, 0, p, 0)
        if (clip[3] <= 0f) return false
        val nxs = clip[0] / clip[3] * 0.5f + 0.5f          // normalized screen x (right)
        val nys = 1f - (clip[1] / clip[3] * 0.5f + 0.5f)    // normalized screen y (down)
        if (nxs < 0f || nxs > 1f || nys < 0f || nys > 1f) return false
        val mw = h.mask.width; val mh = h.mask.height
        val ix = (nys * mw).toInt()                          // normalized screen → image px (tap mapping)
        val iy = ((1f - nxs) * mh).toInt()
        return maskHit(h.mask.binary, mw, mh, ix, iy)
    }

    /** Mask sample with a 1-pixel neighbourhood tolerance (absorbs edge/pose noise). */
    private fun maskHit(binary: BooleanArray, w: Int, h: Int, cx: Int, cy: Int): Boolean {
        var dy = -1
        while (dy <= 1) {
            val y = cy + dy
            if (y in 0 until h) {
                val row = y * w
                var dx = -1
                while (dx <= 1) {
                    val x = cx + dx
                    if (x in 0 until w && binary[row + x]) return true
                    dx++
                }
            }
            dy++
        }
        return false
    }
}
