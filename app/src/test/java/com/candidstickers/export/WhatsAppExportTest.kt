package com.candidstickers.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.candidstickers.data.PackRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class WhatsAppExportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val pack = PackRow(
        id = 1L,
        identifier = "candid-1",
        name = "Pack One",
        publisher = "Candid Stickers",
        trayFile = "tray.png",
        imageDataVersion = 1,
        createdAt = 0L,
        stickerCount = 3,
    )

    @Test
    fun addPackIntentWrapsEnableStickerPackInChooser() {
        val chooser = WhatsAppExport.addPackIntent(pack)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)

        @Suppress("DEPRECATION")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("com.whatsapp.intent.action.ENABLE_STICKER_PACK", inner.action)
        assertEquals("candid-1", inner.getStringExtra("sticker_pack_id"))
        assertEquals(StickerContentProvider.AUTHORITY, inner.getStringExtra("sticker_pack_authority"))
        assertEquals("Pack One", inner.getStringExtra("sticker_pack_name"))
    }

    @Test
    fun parseResultMapsAllOutcomes() {
        assertEquals(WhatsAppExport.AddResult.Added, WhatsAppExport.parseResult(Activity.RESULT_OK, null))
        assertEquals(WhatsAppExport.AddResult.Added, WhatsAppExport.parseResult(Activity.RESULT_OK, Intent()))
        assertEquals(WhatsAppExport.AddResult.Declined, WhatsAppExport.parseResult(Activity.RESULT_CANCELED, null))
        assertEquals(WhatsAppExport.AddResult.Declined, WhatsAppExport.parseResult(Activity.RESULT_CANCELED, Intent()))
        assertEquals(
            WhatsAppExport.AddResult.ValidationError("tray icon too big"),
            WhatsAppExport.parseResult(
                Activity.RESULT_CANCELED,
                Intent().putExtra("validation_error", "tray icon too big"),
            ),
        )
    }

    @Test
    fun isInstalledChecksBothWhatsAppPackages() {
        assertFalse(WhatsAppExport.isInstalled(context))

        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.whatsapp.w4b" }
        )
        assertTrue(WhatsAppExport.isInstalled(context))

        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.whatsapp" }
        )
        assertTrue(WhatsAppExport.isInstalled(context))
    }
}
