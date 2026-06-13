package com.candidstickers.scan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/** Guard-rail and happy-path checks for [FaceAligner.align] (needs Robolectric for Bitmap/Canvas). */
@RunWith(AndroidJUnit4::class)
class FaceAlignerGuardTest {

    /** Keypoints = ArcFace template scaled/offset into source-bitmap space. */
    private fun templateKeypoints(scale: Float, dx: Float, dy: Float): FaceAnalyzer.Keypoints {
        fun p(i: Int) = PointF(
            FaceAligner.TEMPLATE_X[i] * scale + dx,
            FaceAligner.TEMPLATE_Y[i] * scale + dy,
        )
        val leftEye = p(0)
        val rightEye = p(1)
        return FaceAnalyzer.Keypoints(
            leftEye = leftEye,
            rightEye = rightEye,
            nose = p(2),
            mouthLeft = p(3),
            mouthRight = p(4),
            interOcularPx = hypot(rightEye.x - leftEye.x, rightEye.y - leftEye.y),
        )
    }

    private fun solidBitmap(size: Int, color: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun tinyInterOcularReturnsNull() {
        // Template inter-ocular is ~35.2px; scale 1 stays under the 40px floor.
        val k = templateKeypoints(scale = 1f, dx = 0f, dy = 0f)
        assertEquals(35.24f, k.interOcularPx, 0.01f)
        assertNull(FaceAligner.align(solidBitmap(120, Color.RED), k))
    }

    @Test
    fun coincidentKeypointsReturnNull() {
        // interOcularPx lies about the (degenerate) geometry; the solver must still bail.
        val p = PointF(50f, 50f)
        val k = FaceAnalyzer.Keypoints(p, p, p, p, p, interOcularPx = 50f)
        assertNull(FaceAligner.align(solidBitmap(120, Color.RED), k))
    }

    @Test
    fun reflectedKeypointsReturnNull() {
        // Vertically mirrored face (nose/mouth above the eyes): inter-ocular still
        // ~70px so the size guard passes, but no proper similarity can fit it.
        val up = templateKeypoints(scale = 2f, dx = 30f, dy = 0f)
        fun flip(p: PointF) = PointF(p.x, 300f - p.y)
        val k = FaceAnalyzer.Keypoints(
            leftEye = flip(up.leftEye),
            rightEye = flip(up.rightEye),
            nose = flip(up.nose),
            mouthLeft = flip(up.mouthLeft),
            mouthRight = flip(up.mouthRight),
            interOcularPx = up.interOcularPx,
        )
        assertNull(FaceAligner.align(solidBitmap(300, Color.RED), k))
    }

    @Test
    fun alignsTemplateFaceTo112Argb8888() {
        // Keypoints at template*2 + 30: align solves a 0.5x shrink, so every
        // output pixel samples inside the red 300x300 source.
        val source = solidBitmap(300, Color.RED)
        val k = templateKeypoints(scale = 2f, dx = 30f, dy = 30f)

        val out = FaceAligner.align(source, k)
        assertNotNull(out)
        assertEquals(112, out!!.width)
        assertEquals(112, out.height)
        assertEquals(Bitmap.Config.ARGB_8888, out.config)
        for ((x, y) in listOf(0 to 0, 111 to 0, 0 to 111, 111 to 111, 56 to 56)) {
            assertEquals("pixel ($x,$y)", Color.RED, out.getPixel(x, y))
        }
    }

    @Test
    fun reordersSwappedPairsByImageX() {
        // Same geometry as the happy path but eye/mouth pairs handed over in
        // descending-x order; align must re-order before pairing with the template.
        val source = solidBitmap(300, Color.RED)
        val k = templateKeypoints(scale = 2f, dx = 30f, dy = 30f)
        val swapped = FaceAnalyzer.Keypoints(
            leftEye = k.rightEye,
            rightEye = k.leftEye,
            nose = k.nose,
            mouthLeft = k.mouthRight,
            mouthRight = k.mouthLeft,
            interOcularPx = k.interOcularPx,
        )
        val out = FaceAligner.align(source, swapped)
        assertNotNull(out)
        assertEquals(Color.RED, out!!.getPixel(56, 56))
    }
}
