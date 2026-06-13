package com.candidstickers.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.candidstickers.data.CandidCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareStickerTest {

    @Test
    fun sharesCropPngThroughFileProviderChooser() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val file = File(File(app.filesDir, "crops").apply { mkdirs() }, "1_0.png")
        file.writeBytes(byteArrayOf(1))
        val crop = CandidCrop(
            id = 1L,
            mediaId = 1L,
            contentUri = "content://media/1",
            faceIndex = 0,
            score = 0.9f,
            reason = "wink",
            cropPath = file.absolutePath,
        )

        ShareSticker.share(app, crop)

        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("image/png", inner.type)
        assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        @Suppress("DEPRECATION")
        val stream = inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("com.candidstickers.fileprovider", stream.authority)
        assertEquals(listOf("crops", "1_0.png"), stream.pathSegments)
    }
}
