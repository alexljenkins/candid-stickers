package com.candidstickers.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropDbMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
    }

    @Test
    fun upgradeFromV1PreservesDataAndAddsSchema() {
        createV1DatabaseWithData()

        val db = CropDb.getInstance(context)
        val raw = db.readableDatabase
        assertEquals(2, raw.version)

        // v1 rows survived the migration
        assertEquals(setOf(42L), db.scannedMediaIds())
        assertEquals(1, db.cropCount())
        val crops = db.topCrops()
        assertEquals(1, crops.size)
        val crop = crops[0]
        assertEquals(42L, crop.mediaId)
        assertEquals("content://media/external/images/media/42", crop.contentUri)
        assertEquals(0, crop.faceIndex)
        assertEquals(0.87f, crop.score, 1e-6f)
        assertEquals("mid-sneeze", crop.reason)
        assertEquals("/data/crops/42-0.png", crop.cropPath)
        assertEquals(listOf(crop), db.cropsByIds(listOf(crop.id)))

        // new crops columns exist
        val cropCols = tableColumns(raw, "crops")
        assertTrue("person_id missing", "person_id" in cropCols)
        assertTrue("tags missing", "tags" in cropCols)
        assertTrue("face_embedding missing", "face_embedding" in cropCols)
        assertTrue("embedding (v1) missing", "embedding" in cropCols)

        // new tables exist
        for (table in listOf("persons", "packs", "pack_stickers")) {
            assertTrue("$table table missing", tableExists(raw, table))
        }

        // pack API round-trip on the migrated database
        val packId = db.insertPack("candid-pack-1", "Candid Pack", "Alex", 1_700_000_000_000L)
        assertTrue(packId > 0)
        db.insertPackSticker(packId, crop.id, "sticker_01.webp", "😂,🤣", 0)
        db.updatePackTray(packId, "tray.png")
        db.bumpImageDataVersion(packId)

        val pack = db.pack(packId)
        assertNotNull(pack)
        assertEquals("candid-pack-1", pack!!.identifier)
        assertEquals("Candid Pack", pack.name)
        assertEquals("Alex", pack.publisher)
        assertEquals("tray.png", pack.trayFile)
        assertEquals(2, pack.imageDataVersion)
        assertEquals(1_700_000_000_000L, pack.createdAt)
        assertEquals(1, pack.stickerCount)
        assertEquals(pack, db.packByIdentifier("candid-pack-1"))
        assertEquals(listOf(pack), db.packs())
        assertNull(db.packByIdentifier("nope"))

        val stickers = db.packStickers(packId)
        assertEquals(1, stickers.size)
        assertEquals(crop.id, stickers[0].cropId)
        assertEquals("sticker_01.webp", stickers[0].fileName)
        assertEquals("😂,🤣", stickers[0].emojis)
        assertEquals(0, stickers[0].position)
        assertEquals(crop.cropPath, stickers[0].cropPath)

        db.removePackSticker(packId, crop.id)
        assertEquals(0, db.pack(packId)!!.stickerCount)
        db.insertPackSticker(packId, crop.id, "sticker_01.webp", "😂", 0)

        // deletePack cascades pack_stickers (foreign keys enabled in onConfigure)
        db.deletePack(packId)
        assertNull(db.pack(packId))
        raw.rawQuery("SELECT COUNT(*) FROM pack_stickers", null).use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }

    /** Builds a database with raw SQL identical to CropDb v1 onCreate, plus one photo + crop. */
    private fun createV1DatabaseWithData() {
        val file = context.getDatabasePath(CropDb.DATABASE_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE photos(
                    media_id   INTEGER PRIMARY KEY,
                    content_uri TEXT NOT NULL,
                    date_taken INTEGER NOT NULL DEFAULT 0,
                    face_count INTEGER NOT NULL DEFAULT 0,
                    scanned_at INTEGER NOT NULL
                )
                """
            )
            db.execSQL(
                """
                CREATE TABLE crops(
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    media_id   INTEGER NOT NULL REFERENCES photos(media_id) ON DELETE CASCADE,
                    face_index INTEGER NOT NULL,
                    score      REAL NOT NULL,
                    reason     TEXT NOT NULL,
                    crop_path  TEXT NOT NULL,
                    embedding  BLOB
                )
                """
            )
            db.execSQL("CREATE INDEX idx_crops_score ON crops(score DESC)")
            db.execSQL(
                "INSERT INTO photos(media_id, content_uri, date_taken, face_count, scanned_at) " +
                    "VALUES (42, 'content://media/external/images/media/42', 1700000000000, 1, 1700000001000)"
            )
            db.execSQL(
                "INSERT INTO crops(media_id, face_index, score, reason, crop_path) " +
                    "VALUES (42, 0, 0.87, 'mid-sneeze', '/data/crops/42-0.png')"
            )
            db.version = 1
        }
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val cols = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) cols.add(c.getString(nameIdx))
        }
        return cols
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { it.moveToFirst() }
}
