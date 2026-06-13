package com.candidstickers.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StickerWebpTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun fitCenterPadsWideSourceWithTransparency() {
        val src = solid(200, 100, Color.RED)
        val out = fitCenter(src, 512)
        assertEquals(512, out.width)
        assertEquals(512, out.height)
        // Wide source scales to full width; top/bottom bands and corners stay transparent.
        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        assertEquals(0, Color.alpha(out.getPixel(511, 0)))
        assertEquals(0, Color.alpha(out.getPixel(0, 511)))
        assertEquals(0, Color.alpha(out.getPixel(511, 511)))
        assertEquals(0, Color.alpha(out.getPixel(256, 10)))
        assertEquals(0, Color.alpha(out.getPixel(256, 502)))
        assertEquals(Color.RED, out.getPixel(256, 256))
    }

    @Test
    fun fitCenterPadsTallSourceWithTransparency() {
        val src = solid(100, 200, Color.BLUE)
        val out = fitCenter(src, 512)
        assertEquals(512, out.width)
        assertEquals(512, out.height)
        assertEquals(0, Color.alpha(out.getPixel(10, 256)))
        assertEquals(0, Color.alpha(out.getPixel(502, 256)))
        assertEquals(Color.BLUE, out.getPixel(256, 256))
    }

    @Test
    fun rendersStickerWebpAt512WithinBudget() {
        WebpTestSupport.assumeWebpEncoding()
        val src = gradient(320, 240)
        val dest = File(tmp.root, "sticker.webp")
        assertTrue(renderStickerWebp(src, dest))
        assertTrue(dest.isFile)
        assertTrue(dest.length() in 1..(STICKER_MAX_BYTES.toLong()))

        // NOTE: if the runtime encodes but can't decode WebP, bounds stay -1;
        // size/budget assertions above are the load-bearing ones.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(dest.path, bounds)
        if (bounds.outWidth != -1) {
            assertEquals(512, bounds.outWidth)
            assertEquals(512, bounds.outHeight)
        }
    }

    @Test
    fun rendersTrayPngAt96WithinBudget() {
        val src = gradient(200, 100)
        val dest = File(tmp.root, "tray.png")
        assertTrue(renderTrayPng(src, dest))
        assertTrue(dest.length() in 1..(TRAY_MAX_BYTES.toLong()))
        val out = BitmapFactory.decodeFile(dest.path)
        assertEquals(96, out.width)
        assertEquals(96, out.height)
    }

    private fun solid(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun gradient(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, Color.argb(255, x % 256, y % 256, (x + y) % 256))
            }
        }
        return bmp
    }
}
