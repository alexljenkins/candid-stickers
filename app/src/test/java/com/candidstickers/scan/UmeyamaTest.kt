package com.candidstickers.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure-JVM checks of the closed-form Umeyama similarity solver: apply a known
 * scale/rotation/translation to the ArcFace template, solve template' -> template,
 * and require every point to map back within 0.1 px.
 */
class UmeyamaTest {

    private val templateX = FaceAligner.TEMPLATE_X
    private val templateY = FaceAligner.TEMPLATE_Y

    /** Applies q = s·R(theta)·p + t to the template points. */
    private fun transformedTemplate(
        scale: Float,
        thetaRad: Float,
        tx: Float,
        ty: Float,
    ): Pair<FloatArray, FloatArray> {
        val c = cos(thetaRad)
        val s = sin(thetaRad)
        val xs = FloatArray(templateX.size)
        val ys = FloatArray(templateY.size)
        for (i in templateX.indices) {
            xs[i] = scale * (c * templateX[i] - s * templateY[i]) + tx
            ys[i] = scale * (s * templateX[i] + c * templateY[i]) + ty
        }
        return xs to ys
    }

    private fun assertMapsBack(srcX: FloatArray, srcY: FloatArray) {
        val sim = FaceAligner.solve(srcX, srcY, templateX, templateY)
        assertNotNull("solver returned null for a valid similarity", sim)
        for (i in templateX.indices) {
            assertEquals("x[$i]", templateX[i], sim!!.mapX(srcX[i], srcY[i]), 0.1f)
            assertEquals("y[$i]", templateY[i], sim.mapY(srcX[i], srcY[i]), 0.1f)
        }
    }

    @Test
    fun recoversIdentity() {
        val sim = FaceAligner.solve(templateX, templateY, templateX, templateY)!!
        assertEquals(1f, sim.scale, 1e-4f)
        assertEquals(0f, sim.theta, 1e-4f)
        assertEquals(0f, sim.tx, 1e-2f)
        assertEquals(0f, sim.ty, 1e-2f)
        assertMapsBack(templateX, templateY)
    }

    @Test
    fun recoversKnownTransforms() {
        val scales = floatArrayOf(0.4f, 1f, 2.37f, 11f)
        val thetasDeg = floatArrayOf(-179f, -90f, -28.5f, 0f, 13f, 45f, 120f)
        val translations = arrayOf(0f to 0f, 480.5f to -77.25f, -1000f to 2400f)
        for (s in scales) for (deg in thetasDeg) for ((tx, ty) in translations) {
            val theta = Math.toRadians(deg.toDouble()).toFloat()
            val (srcX, srcY) = transformedTemplate(s, theta, tx, ty)
            assertMapsBack(srcX, srcY)
            // The recovered transform is the inverse of the applied one.
            val sim = FaceAligner.solve(srcX, srcY, templateX, templateY)!!
            assertEquals("scale for s=$s deg=$deg t=($tx,$ty)", 1f / s, sim.scale, 1e-3f / s)
        }
    }

    @Test
    fun recoversFromNoisySourcePoints() {
        val (srcX, srcY) = transformedTemplate(3f, 0.3f, 120f, 45f)
        // Sub-pixel jitter on the source; fit error stays within the 0.1px-ish band.
        val jitter = floatArrayOf(0.05f, -0.04f, 0.03f, -0.05f, 0.02f)
        for (i in srcX.indices) {
            srcX[i] += jitter[i]
            srcY[i] -= jitter[(i + 2) % jitter.size]
        }
        assertMapsBack(srcX, srcY)
    }

    @Test
    fun rejectsReflectedInput() {
        // Mirror the transformed template; no 4-DOF similarity can map it back.
        val (srcX, srcY) = transformedTemplate(2f, 0.5f, 50f, 60f)
        val yFlipped = FloatArray(srcY.size) { -srcY[it] }
        assertNull(FaceAligner.solve(srcX, yFlipped, templateX, templateY))

        val xFlipped = FloatArray(srcX.size) { -srcX[it] }
        assertNull(FaceAligner.solve(xFlipped, srcY, templateX, templateY))
    }

    @Test
    fun rejectsCoincidentPoints() {
        val xs = FloatArray(5) { 10f }
        val ys = FloatArray(5) { 20f }
        assertNull(FaceAligner.solve(xs, ys, templateX, templateY))
    }

    @Test
    fun rejectsCollinearTriple() {
        // First three source points on one line: winding is undefined.
        val xs = floatArrayOf(0f, 1f, 2f, 3f, 4f)
        val ys = floatArrayOf(0f, 1f, 2f, 5f, 7f)
        assertNull(FaceAligner.solve(xs, ys, templateX, templateY))
    }
}
