package com.candidstickers.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import androidx.annotation.VisibleForTesting
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ArcFace 5-point alignment: solves a 4-DOF similarity (uniform scale +
 * rotation + translation, no reflection) from the detected keypoints to the
 * canonical ArcFace template and warps the full-resolution photo into the
 * 112x112 crop MobileFaceNet expects.
 */
object FaceAligner {

    const val OUTPUT_SIZE = 112

    /**
     * ArcFace template points in 112x112 pixel space, ordered
     * leftEye, rightEye, nose, mouthLeft, mouthRight (image-left first).
     */
    @VisibleForTesting
    internal val TEMPLATE_X = floatArrayOf(38.2946f, 73.5318f, 56.0252f, 41.5493f, 70.7299f)

    @VisibleForTesting
    internal val TEMPLATE_Y = floatArrayOf(51.6963f, 51.5014f, 71.7366f, 92.3655f, 92.2041f)

    /**
     * Similarity transform `q = s·R(theta)·p + t`. With `a = s·cos(theta)` and
     * `b = s·sin(theta)` a point maps as `(a·x − b·y + tx, b·x + a·y + ty)`.
     */
    data class Similarity(val scale: Float, val theta: Float, val tx: Float, val ty: Float) {
        private val a = scale * cos(theta)
        private val b = scale * sin(theta)

        fun mapX(x: Float, y: Float): Float = a * x - b * y + tx
        fun mapY(x: Float, y: Float): Float = b * x + a * y + ty
    }

    /**
     * Warps [source] so the face described by [k] lands on the ArcFace template
     * in a 112x112 ARGB_8888 bitmap (bilinear sampling).
     *
     * Returns null when the face is too small ([FaceAnalyzer.Keypoints.interOcularPx]
     * below [FaceAnalyzer.MIN_INTER_OCULAR_PX]) or the keypoint geometry is
     * degenerate/reflected, so callers simply skip the face.
     */
    fun align(source: Bitmap, k: FaceAnalyzer.Keypoints): Bitmap? {
        if (k.interOcularPx < FaceAnalyzer.MIN_INTER_OCULAR_PX) return null

        // FaceAnalyzer already orders by image x; re-order defensively so the
        // pairing with the template never depends on the producer.
        val (leftEye, rightEye) = orderByX(k.leftEye, k.rightEye)
        val (mouthLeft, mouthRight) = orderByX(k.mouthLeft, k.mouthRight)

        val srcX = floatArrayOf(leftEye.x, rightEye.x, k.nose.x, mouthLeft.x, mouthRight.x)
        val srcY = floatArrayOf(leftEye.y, rightEye.y, k.nose.y, mouthLeft.y, mouthRight.y)
        val sim = solve(srcX, srcY, TEMPLATE_X, TEMPLATE_Y) ?: return null

        val a = sim.scale * cos(sim.theta)
        val b = sim.scale * sin(sim.theta)
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    a, -b, sim.tx,
                    b, a, sim.ty,
                    0f, 0f, 1f,
                )
            )
        }

        val out = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Least-squares similarity (Umeyama, 4-DOF) mapping `src` onto `dst`.
     *
     * Minimizing `sum ||s·R·p_i + t − q_i||^2` is linear in `alpha = s·cos(theta)`
     * and `beta = s·sin(theta)`: with centered points p̂, q̂,
     * `alpha = a/v`, `beta = b/v` where `a = sum(p̂x·q̂x + p̂y·q̂y)`,
     * `b = sum(p̂x·q̂y − p̂y·q̂x)` and `v = sum(p̂x² + p̂y²)`. Hence
     * `scale = hypot(a, b)/v`, `theta = atan2(b, a)`, `t = q̄ − s·R·p̄`.
     * The parameterization can only produce proper rotations (det = s² > 0),
     * so reflected inputs are detected up front by comparing the orientation
     * of the first three points in `src` and `dst` and rejected with null.
     *
     * @return null when the source points are (near-)coincident, the
     *   orientations disagree (reflection or collinear triple), or the solved
     *   scale is non-finite/degenerate.
     */
    @VisibleForTesting
    internal fun solve(srcX: FloatArray, srcY: FloatArray, dstX: FloatArray, dstY: FloatArray): Similarity? {
        val n = srcX.size
        require(n >= 2 && srcY.size == n && dstX.size == n && dstY.size == n) {
            "Need matching point lists of at least 2 points"
        }

        if (n >= 3) {
            val crossSrc = cross(srcX, srcY)
            val crossDst = cross(dstX, dstY)
            // Opposite winding means the source is a reflection of the target
            // (a 4-DOF similarity cannot fit it); zero means collinear.
            if (crossSrc * crossDst <= 0.0) return null
        }

        var meanSx = 0.0; var meanSy = 0.0; var meanDx = 0.0; var meanDy = 0.0
        for (i in 0 until n) {
            meanSx += srcX[i]; meanSy += srcY[i]
            meanDx += dstX[i]; meanDy += dstY[i]
        }
        meanSx /= n; meanSy /= n; meanDx /= n; meanDy /= n

        var a = 0.0
        var b = 0.0
        var variance = 0.0
        for (i in 0 until n) {
            val sx = srcX[i] - meanSx
            val sy = srcY[i] - meanSy
            val dx = dstX[i] - meanDx
            val dy = dstY[i] - meanDy
            a += sx * dx + sy * dy
            b += sx * dy - sy * dx
            variance += sx * sx + sy * sy
        }
        if (variance < EPSILON) return null

        val scale = hypot(a, b) / variance
        if (!scale.isFinite() || scale < EPSILON) return null
        val theta = atan2(b, a)

        val cosT = cos(theta)
        val sinT = sin(theta)
        val tx = meanDx - scale * (cosT * meanSx - sinT * meanSy)
        val ty = meanDy - scale * (sinT * meanSx + cosT * meanSy)
        return Similarity(scale.toFloat(), theta.toFloat(), tx.toFloat(), ty.toFloat())
    }

    /** Signed cross product (p1−p0)×(p2−p0): the winding of the first three points. */
    private fun cross(x: FloatArray, y: FloatArray): Double {
        val ux = (x[1] - x[0]).toDouble()
        val uy = (y[1] - y[0]).toDouble()
        val vx = (x[2] - x[0]).toDouble()
        val vy = (y[2] - y[0]).toDouble()
        return ux * vy - uy * vx
    }

    private fun orderByX(p: PointF, q: PointF): Pair<PointF, PointF> =
        if (p.x <= q.x) p to q else q to p

    private const val EPSILON = 1e-6
}
