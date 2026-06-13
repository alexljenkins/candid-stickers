package com.candidstickers.clip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * On-device smoke test for the real ORT sessions (onnxruntime-android is an
 * AAR with native libs, so none of this can run on the JVM). Requires the
 * gitignored ONNX assets — run scripts/fetch-models.sh before building.
 *
 * NOTE: intentionally not part of the JVM suite; run via connectedAndroidTest
 * on real hardware only.
 */
@RunWith(AndroidJUnit4::class)
class ClipEncoderSmokeTest {

    @Test
    fun encodersProduceUnitNormDeterministicEmbeddings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoder = ClipEncoder.create(context)
        assertNotNull("CLIP assets missing — run scripts/fetch-models.sh", encoder)
        encoder!!.use {
            val text = it.encodeText("crying laughing")
            assertEquals(ClipEncoder.EMBEDDING_DIM, text.size)
            assertEquals(1f, norm(text), 1e-3f)

            // Deterministic across calls.
            assertArrayEquals(text, it.encodeText("crying laughing"), 1e-5f)

            val image = it.encodeImage(gradientBitmap(640, 480))
            assertEquals(ClipEncoder.EMBEDDING_DIM, image.size)
            assertEquals(1f, norm(image), 1e-3f)

            // Tiny portrait-orientation bitmap exercises the other resize branch.
            val small = it.encodeImage(gradientBitmap(120, 300))
            assertEquals(1f, norm(small), 1e-3f)

            // Self-similarity must beat similarity to an unrelated phrase.
            val unrelated = it.encodeText("a photo of a red sports car")
            assertTrue(
                "self-sim should beat cross-sim",
                dot(text, text) > dot(text, unrelated)
            )
        }
    }

    @Test
    fun clipHolderCachesSingleInstance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Clip.resetForTesting()
        val first = Clip.get(context)
        assertNotNull(first)
        assertSame(first, Clip.get(context))
        assertSame(first, Clip.peek())
        Clip.resetForTesting()
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        return sqrt(sum).toFloat()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun gradientBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.rgb(255, 64, 0), Color.rgb(0, 64, 255), Shader.TileMode.CLAMP
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }
}
