package com.candidstickers.export

import android.graphics.Bitmap
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream

/**
 * Robolectric's native graphics encodes WebP on current versions; the
 * assumption guard skips (not fails) codec-dependent assertions on a runtime
 * that can't, so geometry/contract tests still run everywhere.
 */
object WebpTestSupport {

    val canEncode: Boolean by lazy {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        val ok = try {
            compressWebp(bmp, 80, out)
        } catch (t: Throwable) {
            false
        }
        bmp.recycle()
        ok && out.size() > 0
    }

    fun assumeWebpEncoding() = assumeTrue("Robolectric runtime cannot encode WebP", canEncode)
}
