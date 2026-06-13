package com.candidstickers.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting

data class CandidCrop(
    val id: Long,
    val mediaId: Long,
    val contentUri: String,
    val faceIndex: Int,
    val score: Float,
    val reason: String,
    val cropPath: String,
)

data class PackRow(
    val id: Long,
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayFile: String,
    val imageDataVersion: Int,
    val createdAt: Long,
    val stickerCount: Int,
)

data class PackStickerRow(
    val packId: Long,
    val cropId: Long,
    val fileName: String,
    val emojis: String,
    val position: Int,
    val cropPath: String,
)

/**
 * Plain SQLite (not Room) so we can swap in a sqlite-vec-enabled build later
 * without fighting an ORM. `crops.embedding` is reserved for CLIP (milestone 3);
 * `crops.face_embedding` is reserved for face clustering.
 *
 * Process-wide singleton: both the app (ViewModel/Worker) and the WhatsApp
 * [com.candidstickers.export.StickerContentProvider] read this database, so a
 * single shared connection avoids cross-connection locking. Never call
 * [close] on the instance; it lives for the process.
 */
class CropDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
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
                embedding  BLOB,
                person_id  INTEGER,
                tags       TEXT,
                face_embedding BLOB
            )
            """
        )
        db.execSQL("CREATE INDEX idx_crops_score ON crops(score DESC)")
        createV2Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE crops ADD COLUMN person_id INTEGER")
            db.execSQL("ALTER TABLE crops ADD COLUMN tags TEXT")
            db.execSQL("ALTER TABLE crops ADD COLUMN face_embedding BLOB")
            createV2Tables(db)
        }
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE persons(
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT,
                centroid   BLOB,
                face_count INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE packs(
                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                identifier         TEXT NOT NULL UNIQUE,
                name               TEXT NOT NULL,
                publisher          TEXT NOT NULL,
                tray_file          TEXT NOT NULL DEFAULT '',
                image_data_version INTEGER NOT NULL DEFAULT 1,
                created_at         INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE pack_stickers(
                pack_id   INTEGER NOT NULL REFERENCES packs(id) ON DELETE CASCADE,
                crop_id   INTEGER NOT NULL REFERENCES crops(id),
                file_name TEXT NOT NULL,
                emojis    TEXT NOT NULL,
                position  INTEGER NOT NULL,
                PRIMARY KEY(pack_id, crop_id)
            )
            """
        )
    }

    // ---------------------------------------------------------------- scan

    fun scannedMediaIds(): Set<Long> {
        val ids = HashSet<Long>()
        readableDatabase.rawQuery("SELECT media_id FROM photos", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    fun markScanned(mediaId: Long, contentUri: String, dateTaken: Long, faceCount: Int) {
        val values = ContentValues().apply {
            put("media_id", mediaId)
            put("content_uri", contentUri)
            put("date_taken", dateTaken)
            put("face_count", faceCount)
            put("scanned_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertCrop(mediaId: Long, faceIndex: Int, score: Float, reason: String, cropPath: String): Long {
        val values = ContentValues().apply {
            put("media_id", mediaId)
            put("face_index", faceIndex)
            put("score", score)
            put("reason", reason)
            put("crop_path", cropPath)
        }
        return writableDatabase.insert("crops", null, values)
    }

    fun topCrops(limit: Int = 500): List<CandidCrop> {
        val out = ArrayList<CandidCrop>()
        readableDatabase.rawQuery(
            """
            SELECT c.id, c.media_id, p.content_uri, c.face_index, c.score, c.reason, c.crop_path
            FROM crops c JOIN photos p ON p.media_id = c.media_id
            ORDER BY c.score DESC
            LIMIT ?
            """,
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(readCrop(c))
        }
        return out
    }

    fun cropsByIds(ids: List<Long>): List<CandidCrop> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, CandidCrop>(ids.size)
        ids.chunked(900).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                """
                SELECT c.id, c.media_id, p.content_uri, c.face_index, c.score, c.reason, c.crop_path
                FROM crops c JOIN photos p ON p.media_id = c.media_id
                WHERE c.id IN ($placeholders)
                """,
                chunk.map { it.toString() }.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    val crop = readCrop(c)
                    byId[crop.id] = crop
                }
            }
        }
        // Preserve caller's order; silently drop unknown ids.
        return ids.mapNotNull { byId[it] }
    }

    fun cropCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM crops", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun readCrop(c: android.database.Cursor) = CandidCrop(
        id = c.getLong(0),
        mediaId = c.getLong(1),
        contentUri = c.getString(2),
        faceIndex = c.getInt(3),
        score = c.getFloat(4),
        reason = c.getString(5),
        cropPath = c.getString(6),
    )

    // ---------------------------------------------------------------- packs

    fun insertPack(identifier: String, name: String, publisher: String, createdAt: Long): Long {
        val values = ContentValues().apply {
            put("identifier", identifier)
            put("name", name)
            put("publisher", publisher)
            put("created_at", createdAt)
        }
        return writableDatabase.insert("packs", null, values)
    }

    fun updatePackIdentifier(packId: Long, identifier: String) {
        val values = ContentValues().apply { put("identifier", identifier) }
        writableDatabase.update("packs", values, "id = ?", arrayOf(packId.toString()))
    }

    fun updatePackTray(packId: Long, trayFile: String) {
        val values = ContentValues().apply { put("tray_file", trayFile) }
        writableDatabase.update("packs", values, "id = ?", arrayOf(packId.toString()))
    }

    /**
     * Must be called whenever a pack's stickers or tray icon change —
     * `image_data_version` is WhatsApp's only cache-refresh mechanism.
     */
    fun bumpImageDataVersion(packId: Long) {
        writableDatabase.execSQL(
            "UPDATE packs SET image_data_version = image_data_version + 1 WHERE id = ?",
            arrayOf(packId.toString())
        )
    }

    fun insertPackSticker(packId: Long, cropId: Long, fileName: String, emojis: String, position: Int) {
        val values = ContentValues().apply {
            put("pack_id", packId)
            put("crop_id", cropId)
            put("file_name", fileName)
            put("emojis", emojis)
            put("position", position)
        }
        writableDatabase.insertWithOnConflict("pack_stickers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removePackSticker(packId: Long, cropId: Long) {
        writableDatabase.delete(
            "pack_stickers",
            "pack_id = ? AND crop_id = ?",
            arrayOf(packId.toString(), cropId.toString())
        )
    }

    /** Removes the pack row (pack_stickers cascade). Deleting files on disk is PackManager's job. */
    fun deletePack(packId: Long) {
        writableDatabase.delete("packs", "id = ?", arrayOf(packId.toString()))
    }

    fun packs(): List<PackRow> {
        val out = ArrayList<PackRow>()
        readableDatabase.rawQuery(PACK_SELECT + " ORDER BY p.created_at DESC", null).use { c ->
            while (c.moveToNext()) out.add(readPack(c))
        }
        return out
    }

    fun pack(packId: Long): PackRow? {
        readableDatabase.rawQuery(PACK_SELECT + " WHERE p.id = ?", arrayOf(packId.toString())).use { c ->
            return if (c.moveToFirst()) readPack(c) else null
        }
    }

    fun packByIdentifier(identifier: String): PackRow? {
        readableDatabase.rawQuery(PACK_SELECT + " WHERE p.identifier = ?", arrayOf(identifier)).use { c ->
            return if (c.moveToFirst()) readPack(c) else null
        }
    }

    fun packStickers(packId: Long): List<PackStickerRow> {
        val out = ArrayList<PackStickerRow>()
        readableDatabase.rawQuery(
            """
            SELECT ps.pack_id, ps.crop_id, ps.file_name, ps.emojis, ps.position, c.crop_path
            FROM pack_stickers ps JOIN crops c ON c.id = ps.crop_id
            WHERE ps.pack_id = ?
            ORDER BY ps.position ASC
            """,
            arrayOf(packId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PackStickerRow(
                        packId = c.getLong(0),
                        cropId = c.getLong(1),
                        fileName = c.getString(2),
                        emojis = c.getString(3),
                        position = c.getInt(4),
                        cropPath = c.getString(5),
                    )
                )
            }
        }
        return out
    }

    private fun readPack(c: android.database.Cursor) = PackRow(
        id = c.getLong(0),
        identifier = c.getString(1),
        name = c.getString(2),
        publisher = c.getString(3),
        trayFile = c.getString(4),
        imageDataVersion = c.getInt(5),
        createdAt = c.getLong(6),
        stickerCount = c.getInt(7),
    )

    companion object {
        const val DATABASE_NAME = "candid.db"
        const val DATABASE_VERSION = 2

        private const val PACK_SELECT =
            """
            SELECT p.id, p.identifier, p.name, p.publisher, p.tray_file, p.image_data_version, p.created_at,
                   (SELECT COUNT(*) FROM pack_stickers ps WHERE ps.pack_id = p.id) AS sticker_count
            FROM packs p
            """

        @Volatile
        private var instance: CropDb? = null

        fun getInstance(context: Context): CropDb =
            instance ?: synchronized(this) {
                instance ?: CropDb(context.applicationContext).also { instance = it }
            }

        /** Tests only: close the cached connection so the next getInstance reopens the file. */
        @VisibleForTesting
        fun closeAndResetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
