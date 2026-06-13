package com.candidstickers.export

import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.candidstickers.data.CropDb
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File

@RunWith(AndroidJUnit4::class)
class StickerContentProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority = StickerContentProvider.AUTHORITY
    private val identifier = "candid-1"
    private lateinit var db: CropDb
    private lateinit var provider: StickerContentProvider
    private lateinit var packDir: File
    private var packId = 0L
    private var cropId = 0L
    private lateinit var stickerFile: String

    @Before
    fun setUp() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
        File(context.filesDir, "packs").deleteRecursively()
        db = CropDb.getInstance(context)

        db.markScanned(1L, "content://media/1", 0, 1)
        cropId = db.insertCrop(1L, 0, 0.9f, "wink", "/unused/crop.png")
        packId = db.insertPack(identifier, "Pack One", "Candid Stickers", 123L)
        stickerFile = "$cropId.webp"
        db.insertPackSticker(packId, cropId, stickerFile, "😉,😂", 0)
        db.updatePackTray(packId, "tray.png")

        packDir = PackManager.packDir(context, identifier).apply { mkdirs() }
        File(packDir, stickerFile).writeBytes(byteArrayOf(1, 2, 3, 4))
        File(packDir, "tray.png").writeBytes(byteArrayOf(9, 8, 7))

        provider = Robolectric.buildContentProvider(StickerContentProvider::class.java)
            .create(ProviderInfo().apply { this.authority = this@StickerContentProviderTest.authority })
            .get()
    }

    @After
    fun tearDown() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
        File(context.filesDir, "packs").deleteRecursively()
    }

    private fun query(uri: Uri): Cursor = provider.query(uri, null, null, null, null)

    @Test
    fun metadataCursorHasExactColumnsAndValues() {
        val cursor = query(uri("metadata"))
        assertArrayEquals(StickerContentProvider.METADATA_COLUMNS, cursor.columnNames)
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(identifier, cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_identifier")))
        assertEquals("Pack One", cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_name")))
        assertEquals("Candid Stickers", cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_publisher")))
        assertEquals("tray.png", cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_icon")))
        val versionIdx = cursor.getColumnIndexOrThrow("image_data_version")
        assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(versionIdx)) // string of an int
        assertEquals("1", cursor.getString(versionIdx))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("whatsapp_will_not_cache_stickers")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("animated_sticker_pack")))
    }

    @Test
    fun metadataReflectsVersionBump() {
        db.bumpImageDataVersion(packId)
        val cursor = query(uri("metadata", identifier))
        assertTrue(cursor.moveToFirst())
        assertEquals("2", cursor.getString(cursor.getColumnIndexOrThrow("image_data_version")))
    }

    @Test
    fun singlePackMetadataReturnsOneRow() {
        val cursor = query(uri("metadata", identifier))
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(identifier, cursor.getString(0))
    }

    @Test
    fun unknownPackMetadataIsEmptyCursorWithFullColumns() {
        val cursor = query(uri("metadata", "nope"))
        assertEquals(0, cursor.count)
        assertArrayEquals(StickerContentProvider.METADATA_COLUMNS, cursor.columnNames)
    }

    @Test
    fun stickersCursorListsPackStickers() {
        val cursor = query(uri("stickers", identifier))
        assertArrayEquals(StickerContentProvider.STICKER_COLUMNS, cursor.columnNames)
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(stickerFile, cursor.getString(0))
        assertEquals("😉,😂", cursor.getString(1))
        assertTrue(cursor.isNull(2)) // accessibility text is nullable
    }

    @Test
    fun stickersForUnknownPackIsEmpty() {
        assertEquals(0, query(uri("stickers", "nope")).count)
    }

    @Test
    fun queryUnknownUriThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.query(uri("bogus"), null, null, null, null)
        }
    }

    @Test
    fun openAssetFileServesKnownStickerAndTray() {
        val sticker = provider.openAssetFile(uri("stickers_asset", identifier, stickerFile), "r")
        assertNotNull(sticker)
        sticker!!.createInputStream().use {
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), it.readBytes())
        }
        val tray = provider.openAssetFile(uri("stickers_asset", identifier, "tray.png"), "r")
        assertNotNull(tray)
        tray!!.createInputStream().use {
            assertArrayEquals(byteArrayOf(9, 8, 7), it.readBytes())
        }
    }

    @Test
    fun openAssetFileRejectsUnknownPackOrFile() {
        assertNull(provider.openAssetFile(uri("stickers_asset", "ghost", stickerFile), "r"))
        // File exists on disk but is not a DB row -> not served.
        File(packDir, "rogue.webp").writeBytes(byteArrayOf(5))
        assertNull(provider.openAssetFile(uri("stickers_asset", identifier, "rogue.webp"), "r"))
    }

    @Test
    fun openAssetFileRejectsPathTraversal() {
        // A real file one level up that a traversal would reach.
        File(context.filesDir, "packs/secret.webp").writeBytes(byteArrayOf(6))
        val encoded = Uri.parse("content://$authority/stickers_asset/$identifier/..%2Fsecret.webp")
        assertNull(provider.openAssetFile(encoded, "r"))
        val extraSegments = Uri.parse("content://$authority/stickers_asset/$identifier/../secret.webp")
        assertNull(provider.openAssetFile(extraSegments, "r"))
    }

    @Test
    fun getTypeMatchesContract() {
        assertEquals("vnd.android.cursor.dir/vnd.$authority.metadata", provider.getType(uri("metadata")))
        assertEquals("vnd.android.cursor.item/vnd.$authority.metadata", provider.getType(uri("metadata", identifier)))
        assertEquals("vnd.android.cursor.dir/vnd.$authority.stickers", provider.getType(uri("stickers", identifier)))
        assertEquals("image/webp", provider.getType(uri("stickers_asset", identifier, stickerFile)))
        assertEquals("image/png", provider.getType(uri("stickers_asset", identifier, "tray.png")))
        assertThrows(IllegalArgumentException::class.java) { provider.getType(uri("bogus")) }
    }

    @Test
    fun writesAreUnsupported() {
        assertThrows(UnsupportedOperationException::class.java) {
            provider.insert(uri("metadata"), ContentValues())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.update(uri("metadata"), ContentValues(), null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.delete(uri("metadata"), null, null)
        }
    }

    private fun uri(vararg segments: String): Uri {
        val builder = Uri.Builder().scheme("content").authority(authority)
        segments.forEach { builder.appendPath(it) }
        return builder.build()
    }
}
