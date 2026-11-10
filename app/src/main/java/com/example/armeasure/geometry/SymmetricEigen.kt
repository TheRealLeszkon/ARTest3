package com.example.armeasure.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Eigen-decomposition of a **symmetric 3×3** matrix via the cyclic **Jacobi** algorithm. We only
 * ever feed it a covariance matrix (symmetric, real eigenvalues), so Jacobi is ideal: a handful of
 * plane rotations zero out the off-diagonal terms; the accumulated rotations are the eigenvectors,
 * the final diagonal the eigenvalues. Done in double for stability, sorted **descending** (largest
 * spread first).
 *
 * (Ported from ARTest2 `util/SymmetricEigen.kt`.)
 */
object SymmetricEigen {

    class Result(
        /** Eigenvalues, descending. */
        val values: DoubleArray,
        /** Unit eigenvectors (each length 3), aligned with [values]. */
        val vectors: Array<DoubleArray>,
    )

    /** @param m row-major symmetric 3×3 (length 9). */
    fun decompose(m: DoubleArray): Result {
        val a = m.copyOf()
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val pairs = arrayOf(intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 2))

        repeat(MAX_SWEEPS) {
            var off = 0.0
            for ((p, q) in pairs) off += a[p * 3 + q] * a[p * 3 + q]
            if (off < EPS) return@repeat

            for ((p, q) in pairs) {
                val apq = a[p * 3 + q]
                if (abs(apq) < EPS) continue
                val app = a[p * 3 + p]
                val aqq = a[q * 3 + q]
                val theta = (aqq - app) / (2.0 * apq)
                val sign = if (theta >= 0) 1.0 else -1.0
                val t = sign / (abs(theta) + sqrt(theta * theta + 1.0))
                val c = 1.0 / sqrt(t * t + 1.0)
                val s = t * c
                val tau = s / (1.0 + c)

                a[p * 3 + p] = app - t * apq
                a[q * 3 + q] = aqq + t * apq
                a[p * 3 + q] = 0.0
                a[q * 3 + p] = 0.0
                for (r in 0..2) {
                    if (r == p || r == q) continue
                    val arp = a[r * 3 + p]
                    val arq = a[r * 3 + q]
                    a[r * 3 + p] = arp - s * (arq + tau * arp)
                    a[p * 3 + r] = a[r * 3 + p]
                    a[r * 3 + q] = arq + s * (arp - tau * arq)
                    a[q * 3 + r] = a[r * 3 + q]
                }
                for (r in 0..2) {
                    val vrp = v[r * 3 + p]
                    val vrq = v[r * 3 + q]
                    v[r * 3 + p] = vrp - s * (vrq + tau * vrp)
                    v[r * 3 + q] = vrq + s * (vrp - tau * vrq)
                }
            }
        }

        val eig = doubleArrayOf(a[0], a[4], a[8])
        val vecs = Array(3) { j -> doubleArrayOf(v[j], v[3 + j], v[6 + j]) }

        val order = intArrayOf(0, 1, 2).sortedByDescending { eig[it] }
        return Result(
            DoubleArray(3) { eig[order[it]] },
            Array(3) { normalize(vecs[order[it]]) },
        )
    }

    private fun normalize(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (n < 1e-12) doubleArrayOf(0.0, 0.0, 0.0) else doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private const val MAX_SWEEPS = 20
    private const val EPS = 1e-18
}
