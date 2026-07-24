package com.candidstickers

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.candidstickers.clip.Clip
import com.candidstickers.data.CropDb
import com.candidstickers.export.PackManager
import com.candidstickers.export.StickerContentProvider
import com.candidstickers.scan.ScanPipeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Full-pipeline instrumented test: seed MediaStore with real photos (bundled
 * as androidTest assets), run [ScanPipeline] end to end (face detect -> meme
 * score -> matte -> crop -> CLIP tags -> person clustering), build a WhatsApp
 * pack via [PackManager], and read it back through [StickerContentProvider].
 *
 * One orchestrating @Test keeps the stage ordering explicit; each stage logs
 * under [TAG] so the harness can pull observations from logcat afterwards.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndScanTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_MEDIA_IMAGES)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val resolver = targetContext.contentResolver

    /** MediaStore rows this test created (deleted in [cleanUpMediaStore]). */
    private val insertedUris = ArrayList<Uri>()

    @After
    fun cleanUpMediaStore() {
        for (uri in insertedUris) {
            runCatching { resolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "cleanup failed for $uri", it) }
        }
        insertedUris.clear()
    }

    @Test
    fun scanTagsClustersPacksAndProvider() = runBlocking {
        // ---------------------------------------------------- (a) seed photos
        val assetNames = testContext.assets.list(ASSET_DIR)?.sorted().orEmpty()
        assertTrue("no bundled test images under $ASSET_DIR", assetNames.isNotEmpty())
        val mediaIdsByAsset = HashMap<String, Long>()
        assetNames.forEachIndexed { index, name ->
            mediaIdsByAsset[name] = insertIntoMediaStore(name, index)
        }
        Log.i(TAG, "inserted ${mediaIdsByAsset.size} MediaStore images: $mediaIdsByAsset")

        // ----------------------------------------------------- (b) scan
        val db = CropDb.getInstance(targetContext)
        val found = ScanPipeline(targetContext, db).scan(
            onProgress = { Log.i(TAG, "scan progress: $it") }
        )
        val crops = db.topCrops()
        Log.i(TAG, "scan complete: found=$found cropCount=${crops.size}")
        for (crop in crops) {
            Log.i(
                TAG,
                "crop id=${crop.id} media=${crop.mediaId} score=${crop.score} " +
                    "reason=${crop.reason} tags=${crop.tags} person=${crop.personId} " +
                    "path=${crop.cropPath} corners=${cornerAlphaSummary(crop.cropPath)}"
            )
        }
        assertTrue("scan found no crops (found=$found, db=${crops.size})", crops.isNotEmpty())

        // ----------------------------------------------------- (c) CLIP tags
        val clipAvailable = Clip.get(targetContext) != null
        if (clipAvailable) {
            val tagged = crops.count { it.tags.isNotEmpty() }
            Log.i(TAG, "CLIP available; $tagged/${crops.size} crops have tags")
            assertTrue("CLIP encoder ran but no crop has tags", tagged >= 1)
        } else {
            Log.w(TAG, "SKIP tag assert: CLIP assets missing (run scripts/fetch-models.sh)")
        }

        // ------------------------------------------ (d) person clustering
        val persons = db.persons()
        Log.i(TAG, "persons: $persons")
        assertTrue("no persons clustered", persons.isNotEmpty())
        val samePersonMediaIds = SAME_PERSON_ASSETS.mapNotNull { mediaIdsByAsset[it] }.toSet()
        val samePersonCrops = crops.filter { it.mediaId in samePersonMediaIds && it.personId != null }
        Log.i(
            TAG,
            "same-person assets produced ${samePersonCrops.size} clustered crops, " +
                "personIds=${samePersonCrops.map { it.personId }}"
        )
        if (samePersonCrops.size >= 2) {
            assertTrue(
                "same-person photos produced ${samePersonCrops.size} crops but no person " +
                    "has faceCount >= 2: $persons",
                persons.any { it.faceCount >= 2 }
            )
        } else {
            Log.w(TAG, "SKIP same-person assert: <2 crops from the same-person photos")
        }

        // --------------------------------------------------- (e) pack export
        if (crops.size < PackManager.MIN_STICKERS) {
            Log.w(TAG, "SKIP pack assert: only ${crops.size} crops (< ${PackManager.MIN_STICKERS})")
            return@runBlocking
        }
        val packIds = crops.take(PackManager.MIN_STICKERS).map { it.id }
        val pack = PackManager(targetContext).createPack("E2E Pack", packIds).getOrElse {
            throw AssertionError("createPack failed: ${it.message}", it)
        }
        Log.i(TAG, "created pack id=${pack.id} identifier=${pack.identifier} stickers=${pack.stickerCount}")
        assertEquals(packIds.size, pack.stickerCount)

        val dir = PackManager.packDir(targetContext, pack.identifier)
        val stickers = db.packStickers(pack.id)
        assertEquals(packIds.size, stickers.size)
        for (sticker in stickers) {
            val file = File(dir, sticker.fileName)
            assertTrue("sticker file missing: $file", file.isFile)
            assertTrue("sticker ${file.name} is ${file.length()} bytes (> 100KiB)", file.length() <= 100 * 1024)
            val bmp = BitmapFactory.decodeFile(file.path)
                ?: throw AssertionError("sticker ${file.name} is not a decodable image")
            try {
                assertEquals("sticker width", 512, bmp.width)
                assertEquals("sticker height", 512, bmp.height)
            } finally {
                bmp.recycle()
            }
            Log.i(TAG, "sticker ok: ${file.name} ${file.length()} bytes 512x512")
        }
        val tray = File(dir, PackManager.TRAY_FILE_NAME)
        assertTrue("tray missing: $tray", tray.isFile)
        assertTrue("tray is ${tray.length()} bytes (> 50KiB)", tray.length() <= 50 * 1024)
        val trayBmp = BitmapFactory.decodeFile(tray.path)
            ?: throw AssertionError("tray is not a decodable image")
        try {
            assertEquals("tray width", 96, trayBmp.width)
            assertEquals("tray height", 96, trayBmp.height)
        } finally {
            trayBmp.recycle()
        }

        // --------------------------------------- (f) WhatsApp content provider
        val metadataUri = Uri.parse("content://${StickerContentProvider.AUTHORITY}/metadata")
        val cursor = resolver.query(metadataUri, null, null, null, null)
            ?: throw AssertionError("provider returned null for $metadataUri")
        var sawPack = false
        cursor.use { c ->
            assertArrayEquals(
                "metadata columns differ from the WhatsApp contract",
                StickerContentProvider.METADATA_COLUMNS,
                c.columnNames
            )
            while (c.moveToNext()) {
                val identifier = c.getString(c.getColumnIndexOrThrow("sticker_pack_identifier"))
                if (identifier != pack.identifier) continue
                sawPack = true
                assertEquals("E2E Pack", c.getString(c.getColumnIndexOrThrow("sticker_pack_name")))
                assertEquals(PackManager.PUBLISHER, c.getString(c.getColumnIndexOrThrow("sticker_pack_publisher")))
                assertEquals(PackManager.TRAY_FILE_NAME, c.getString(c.getColumnIndexOrThrow("sticker_pack_icon")))
                assertTrue(
                    "image_data_version must be a positive int string",
                    c.getString(c.getColumnIndexOrThrow("image_data_version")).toInt() >= 1
                )
            }
        }
        assertTrue("created pack ${pack.identifier} missing from provider metadata", sawPack)

        val stickersUri = Uri.parse(
            "content://${StickerContentProvider.AUTHORITY}/${StickerContentProvider.STICKERS}/${pack.identifier}"
        )
        resolver.query(stickersUri, null, null, null, null)!!.use { c ->
            assertArrayEquals(StickerContentProvider.STICKER_COLUMNS, c.columnNames)
            assertEquals("provider sticker row count", packIds.size, c.count)
        }

        // Asset fetch through the provider (what WhatsApp actually does).
        val assetUri = Uri.parse(
            "content://${StickerContentProvider.AUTHORITY}/${StickerContentProvider.STICKERS_ASSET}/" +
                "${pack.identifier}/${stickers.first().fileName}"
        )
        resolver.openAssetFileDescriptor(assetUri, "r").use { afd ->
            assertTrue("provider could not open sticker asset $assetUri", afd != null)
        }
        Log.i(TAG, "provider contract verified for pack ${pack.identifier}")
    }

    /** Inserts one bundled asset into MediaStore (Pictures/CandidTest) using the IS_PENDING protocol. */
    private fun insertIntoMediaStore(assetName: String, index: Int): Long {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "e2e_$assetName")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis() - index * 1000L)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CandidTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore insert failed for $assetName")
        insertedUris.add(uri)
        resolver.openOutputStream(uri)!!.use { out ->
            testContext.assets.open("$ASSET_DIR/$assetName").use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return ContentUris.parseId(uri)
    }

    /**
     * "transparent" when all four corner pixels of the crop PNG have alpha 0
     * (ML Kit matte ran), "opaque" when none do (flat-crop fallback). Logged,
     * never asserted: the segmentation module may be unavailable on emulators.
     */
    private fun cornerAlphaSummary(path: String): String {
        val bmp = BitmapFactory.decodeFile(path) ?: return "undecodable"
        return try {
            val w = bmp.width - 1
            val h = bmp.height - 1
            val alphas = listOf(
                bmp.getPixel(0, 0), bmp.getPixel(w, 0), bmp.getPixel(0, h), bmp.getPixel(w, h)
            ).map { it ushr 24 }
            when {
                alphas.all { it == 0 } -> "transparent($alphas)"
                alphas.any { it == 0 } -> "partial($alphas)"
                else -> "opaque($alphas)"
            }
        } finally {
            bmp.recycle()
        }
    }

    private companion object {
        const val TAG = "E2ETest"
        const val ASSET_DIR = "testimages"
        val SAME_PERSON_ASSETS = listOf("person1_a.jpg", "person1_b.jpg", "person1_c.jpg")
    }
}
