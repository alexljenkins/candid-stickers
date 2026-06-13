package com.candidstickers.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.candidstickers.data.CropDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PackManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CropDb
    private lateinit var manager: PackManager
    private lateinit var cropsDir: File
    private var nextMediaId = 100L

    @Before
    fun setUp() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
        File(context.filesDir, "packs").deleteRecursively()
        cropsDir = File(context.filesDir, "crops").apply {
            deleteRecursively()
            mkdirs()
        }
        db = CropDb.getInstance(context)
        manager = PackManager(context)
    }

    @After
    fun tearDown() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
    }

    @Test
    fun createPackGeneratesRowsFilesAndTray() {
        WebpTestSupport.assumeWebpEncoding()
        val ids = listOf(insertCrop("jaw drop"), insertCrop("wink"), insertCrop("eyes shut"))

        val pack = runBlocking { manager.createPack("Best Of", ids) }.getOrThrow()

        assertEquals(Emoji.packIdentifier(pack.id), pack.identifier)
        assertEquals("Best Of", pack.name)
        assertEquals(PackManager.PUBLISHER, pack.publisher)
        assertEquals(3, pack.stickerCount)
        assertEquals(PackManager.TRAY_FILE_NAME, pack.trayFile)
        assertEquals(1, pack.imageDataVersion)

        val dir = manager.packDir(pack.identifier)
        ids.forEach { assertTrue(File(dir, "$it.webp").isFile) }
        assertTrue(File(dir, PackManager.TRAY_FILE_NAME).isFile)

        val stickers = manager.stickers(pack.id)
        assertEquals(ids, stickers.map { it.cropId })
        assertEquals(listOf(0, 1, 2), stickers.map { it.position })
        assertEquals(Emoji.forReason("jaw drop").joinToString(","), stickers[0].emojis)
        assertEquals(Emoji.forReason("wink").joinToString(","), stickers[1].emojis)
        assertEquals(listOf(pack), manager.packs())
    }

    @Test
    fun createPackRejectsTooFewStickers() {
        val result = runBlocking { manager.createPack("tiny", listOf(1L, 2L)) }
        assertTrue(result.exceptionOrNull() is PackManager.PackError.TooFewStickers)
        assertTrue(manager.packs().isEmpty())
    }

    @Test
    fun createPackRejectsTooManyStickers() {
        val result = runBlocking { manager.createPack("huge", (1L..31L).toList()) }
        assertTrue(result.exceptionOrNull() is PackManager.PackError.TooManyStickers)
        assertTrue(manager.packs().isEmpty())
    }

    @Test
    fun createPackRejectsUnknownCrops() {
        val result = runBlocking { manager.createPack("ghosts", listOf(998L, 999L, 1000L)) }
        assertTrue(result.exceptionOrNull() is PackManager.PackError.CropsMissing)
        assertTrue(manager.packs().isEmpty())
    }

    @Test
    fun addToPackRendersFileAndBumpsVersion() {
        WebpTestSupport.assumeWebpEncoding()
        val ids = listOf(insertCrop(), insertCrop(), insertCrop())
        val pack = runBlocking { manager.createPack("p", ids) }.getOrThrow()
        val extra = insertCrop("squint")

        val updated = runBlocking { manager.addToPack(pack.id, listOf(extra)) }.getOrThrow()

        assertEquals(4, updated.stickerCount)
        assertEquals(2, updated.imageDataVersion)
        assertTrue(File(manager.packDir(pack.identifier), "$extra.webp").isFile)
        val stickers = manager.stickers(pack.id)
        assertEquals(extra, stickers.last().cropId)
        assertEquals(3, stickers.last().position)
    }

    @Test
    fun addToPackRejectsOverflowWithoutBumping() {
        WebpTestSupport.assumeWebpEncoding()
        val ids = listOf(insertCrop(), insertCrop(), insertCrop())
        val pack = runBlocking { manager.createPack("p", ids) }.getOrThrow()

        val result = runBlocking { manager.addToPack(pack.id, (5000L..5027L).toList()) }

        assertTrue(result.exceptionOrNull() is PackManager.PackError.TooManyStickers)
        assertEquals(1, manager.packs().single().imageDataVersion)
        assertEquals(3, manager.packs().single().stickerCount)
    }

    @Test
    fun addToPackUnknownPackFails() {
        val result = runBlocking { manager.addToPack(404L, listOf(1L)) }
        assertTrue(result.exceptionOrNull() is PackManager.PackError.PackMissing)
    }

    @Test
    fun removeStickerDeletesFileBumpsVersionAndEnforcesFloor() {
        WebpTestSupport.assumeWebpEncoding()
        val ids = listOf(insertCrop(), insertCrop(), insertCrop(), insertCrop())
        val pack = runBlocking { manager.createPack("p", ids) }.getOrThrow()
        val victim = ids.last()

        val updated = runBlocking { manager.removeSticker(pack.id, victim) }.getOrThrow()
        assertEquals(3, updated.stickerCount)
        assertEquals(2, updated.imageDataVersion)
        assertFalse(File(manager.packDir(pack.identifier), "$victim.webp").exists())

        // Floor: 3 stickers must remain; user should delete the pack instead.
        val result = runBlocking { manager.removeSticker(pack.id, ids[0]) }
        assertTrue(result.exceptionOrNull() is PackManager.PackError.TooFewStickers)
        assertEquals(3, manager.packs().single().stickerCount)
        assertEquals(2, manager.packs().single().imageDataVersion)
    }

    @Test
    fun removingFirstStickerRegeneratesTray() {
        WebpTestSupport.assumeWebpEncoding()
        val red = insertCrop(color = Color.RED)
        val green = insertCrop(color = Color.GREEN)
        val rest = listOf(insertCrop(color = Color.BLUE), insertCrop(color = Color.YELLOW))
        val pack = runBlocking { manager.createPack("p", listOf(red, green) + rest) }.getOrThrow()

        val tray = File(manager.packDir(pack.identifier), PackManager.TRAY_FILE_NAME)
        assertEquals(Color.RED, BitmapFactory.decodeFile(tray.path).getPixel(48, 48))

        runBlocking { manager.removeSticker(pack.id, red) }.getOrThrow()
        assertEquals(Color.GREEN, BitmapFactory.decodeFile(tray.path).getPixel(48, 48))
    }

    @Test
    fun deletePackRemovesRowsAndFiles() {
        WebpTestSupport.assumeWebpEncoding()
        val ids = listOf(insertCrop(), insertCrop(), insertCrop())
        val pack = runBlocking { manager.createPack("p", ids) }.getOrThrow()
        val dir = manager.packDir(pack.identifier)
        assertTrue(dir.isDirectory)

        runBlocking { manager.deletePack(pack.id) }

        assertTrue(manager.packs().isEmpty())
        assertTrue(manager.stickers(pack.id).isEmpty())
        assertNull(db.pack(pack.id))
        assertFalse(dir.exists())
    }

    private fun insertCrop(reason: String = "jaw drop", color: Int = Color.RED): Long {
        val mediaId = nextMediaId++
        db.markScanned(mediaId, "content://media/$mediaId", 0, 1)
        val file = File(cropsDir, "${mediaId}_0.png")
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return db.insertCrop(mediaId, 0, 0.9f, reason, file.absolutePath)
    }
}
