package com.candidstickers.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * On-device smoke test: real LiteRT interpreter over the bundled
 * mobilefacenet.tflite. Not runnable on the JVM (native TFLite), so it lives
 * in androidTest and is excluded from the Robolectric suite.
 */
@RunWith(AndroidJUnit4::class)
class FaceEmbedderSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun faceLikeBitmap(bg: Int, feature: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(bg)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = feature }
        canvas.drawCircle(38f, 52f, 8f, paint)  // left eye
        canvas.drawCircle(74f, 52f, 8f, paint)  // right eye
        canvas.drawRect(42f, 86f, 71f, 96f, paint) // mouth
        return bitmap
    }

    @Test
    fun embedsTo192UnitNormVector() {
        FaceEmbedder(context).use { embedder ->
            val embedding = embedder.embed(faceLikeBitmap(Color.LTGRAY, Color.DKGRAY))
            assertEquals(192, embedding.size)

            val norm = sqrt(embedding.sumOf { (it * it).toDouble() })
            assertEquals(1.0, norm, 1e-3)
        }
    }

    @Test
    fun differentInputsProduceDifferentEmbeddings() {
        FaceEmbedder(context).use { embedder ->
            val a = embedder.embed(faceLikeBitmap(Color.LTGRAY, Color.DKGRAY))
            val b = embedder.embed(faceLikeBitmap(Color.DKGRAY, Color.WHITE))
            // Embeddings come from the model, not a constant path: cosine < 1.
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            assertTrue("cosine $dot should differ from 1", dot < 0.999f)
        }
    }

    @Test
    fun sameInputIsDeterministic() {
        FaceEmbedder(context).use { embedder ->
            val bitmap = faceLikeBitmap(Color.LTGRAY, Color.DKGRAY)
            val a = embedder.embed(bitmap)
            val b = embedder.embed(bitmap)
            for (i in a.indices) {
                assertEquals("component $i", a[i], b[i], 1e-5f)
            }
        }
    }

    @Test
    fun rejectsNon112Input() {
        FaceEmbedder(context).use { embedder ->
            try {
                embedder.embed(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
                fail("expected IllegalArgumentException for non-112x112 input")
            } catch (expected: IllegalArgumentException) {
                // guard works
            }
        }
    }
}
