package com.candidstickers.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import org.json.JSONArray

data class CandidCrop(
    val id: Long,
    val mediaId: Long,
    val contentUri: String,
    val faceIndex: Int,
    val score: Float,
    val reason: String,
    val cropPath: String,
    val tags: List<String> = emptyList(),
    val personId: Long? = null,
)

data class PersonRow(
    val id: Long,
    val name: String?,
    val faceCount: Int,
    val coverCropPath: String?,
)

/** One row of `persons` for the clustering loop; [centroid] is a [FloatBlob] of the L2-normalized mean. */
data class PersonCentroid(
    val id: Long,
    val centroid: ByteArray,
    val faceCount: Int,
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
 * without fighting an ORM. `crops.embedding` holds the CLIP image embedding,
 * `crops.face_embedding` the MobileFaceNet embedding (both [FloatBlob]s).
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
        createV3Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE crops ADD COLUMN person_id INTEGER")
            db.execSQL("ALTER TABLE crops ADD COLUMN tags TEXT")
            db.execSQL("ALTER TABLE crops ADD COLUMN face_embedding BLOB")
            createV2Tables(db)
        }
        if (oldVersion < 3) {
            createV3Tables(db)
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

    private fun createV3Tables(db: SQLiteDatabase) {
        // Cached CLIP text embeddings for the tag vocabulary (FloatBlob float32).
        db.execSQL(
            """
            CREATE TABLE tag_bank(
                phrase    TEXT PRIMARY KEY,
                embedding BLOB NOT NULL
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

    fun topCrops(limit: Int = 500): List<CandidCrop> =
        queryCrops("$CROP_SELECT ORDER BY c.score DESC LIMIT ?", arrayOf(limit.toString()))

    fun cropsByIds(ids: List<Long>): List<CandidCrop> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, CandidCrop>(ids.size)
        ids.chunked(900).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            queryCrops(
                "$CROP_SELECT WHERE c.id IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray()
            ).forEach { byId[it.id] = it }
        }
        // Preserve caller's order; silently drop unknown ids.
        return ids.mapNotNull { byId[it] }
    }

    fun cropCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM crops", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun queryCrops(sql: String, args: Array<String>?): List<CandidCrop> {
        val out = ArrayList<CandidCrop>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(readCrop(c))
        }
        return out
    }

    private fun readCrop(c: android.database.Cursor) = CandidCrop(
        id = c.getLong(0),
        mediaId = c.getLong(1),
        contentUri = c.getString(2),
        faceIndex = c.getInt(3),
        score = c.getFloat(4),
        reason = c.getString(5),
        cropPath = c.getString(6),
        tags = parseTags(if (c.isNull(7)) null else c.getString(7)),
        personId = if (c.isNull(8)) null else c.getLong(8),
    )

    private fun parseTags(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ----------------------------------------------------------- clip + tags

    /**
     * [embedding] is a [FloatBlob] CLIP image embedding; [tagsJson] a JSON
     * array of phrases. A zero-length [embedding] is the never-retry sentinel
     * for crops whose PNG can no longer be decoded — it satisfies
     * [cropsMissingClip] without ever entering [cropEmbeddings].
     */
    fun updateCropClip(cropId: Long, embedding: ByteArray, tagsJson: String) {
        val values = ContentValues().apply {
            put("embedding", embedding)
            put("tags", tagsJson)
        }
        writableDatabase.update("crops", values, "id = ?", arrayOf(cropId.toString()))
    }

    fun cropsMissingClip(limit: Int = 50): List<CandidCrop> =
        queryCrops(
            "$CROP_SELECT WHERE c.embedding IS NULL ORDER BY c.score DESC LIMIT ?",
            arrayOf(limit.toString())
        )

    /** All crop CLIP embeddings (id -> FloatBlob), skipping crops not yet embedded and sentinel rows. */
    fun cropEmbeddings(): List<Pair<Long, ByteArray>> {
        val out = ArrayList<Pair<Long, ByteArray>>()
        readableDatabase.rawQuery(
            "SELECT id, embedding FROM crops WHERE embedding IS NOT NULL AND length(embedding) > 0", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getBlob(1))
        }
        return out
    }

    fun tagBank(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        readableDatabase.rawQuery("SELECT phrase, embedding FROM tag_bank", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getBlob(1)
        }
        return out
    }

    fun putTagBank(phrase: String, embedding: ByteArray) {
        val values = ContentValues().apply {
            put("phrase", phrase)
            put("embedding", embedding)
        }
        writableDatabase.insertWithOnConflict("tag_bank", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------------------------------------------------------- faces

    fun updateCropFace(cropId: Long, personId: Long, faceEmbedding: ByteArray) {
        val values = ContentValues().apply {
            put("person_id", personId)
            put("face_embedding", faceEmbedding)
        }
        writableDatabase.update("crops", values, "id = ?", arrayOf(cropId.toString()))
    }

    fun cropsMissingFace(limit: Int = 50): List<CandidCrop> =
        queryCrops(
            "$CROP_SELECT WHERE c.face_embedding IS NULL ORDER BY c.score DESC LIMIT ?",
            arrayOf(limit.toString())
        )

    /**
     * Never-retry sentinel for crops whose face can never be embedded (too
     * small to align, source photo gone): a zero-length `face_embedding` blob
     * satisfies [cropsMissingFace] while staying distinguishable from a real
     * embedding. Future readers of face embeddings must skip empty blobs.
     */
    fun markCropFaceNone(cropId: Long) {
        val values = ContentValues().apply { put("face_embedding", ByteArray(0)) }
        writableDatabase.update("crops", values, "id = ?", arrayOf(cropId.toString()))
    }

    fun cropsForPerson(personId: Long): List<CandidCrop> =
        queryCrops(
            "$CROP_SELECT WHERE c.person_id = ? ORDER BY c.score DESC",
            arrayOf(personId.toString())
        )

    /** Creates a person from its first face: face_count starts at 1. */
    fun insertPerson(centroid: ByteArray): Long {
        val values = ContentValues().apply {
            put("centroid", centroid)
            put("face_count", 1)
        }
        return writableDatabase.insert("persons", null, values)
    }

    fun persons(): List<PersonRow> {
        val out = ArrayList<PersonRow>()
        readableDatabase.rawQuery(
            """
            SELECT pe.id, pe.name, pe.face_count,
                   (SELECT c.crop_path FROM crops c
                    WHERE c.person_id = pe.id
                    ORDER BY c.score DESC LIMIT 1) AS cover
            FROM persons pe
            ORDER BY pe.face_count DESC, pe.id ASC
            """,
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PersonRow(
                        id = c.getLong(0),
                        name = if (c.isNull(1)) null else c.getString(1),
                        faceCount = c.getInt(2),
                        coverCropPath = if (c.isNull(3)) null else c.getString(3),
                    )
                )
            }
        }
        return out
    }

    fun personCentroids(): List<PersonCentroid> {
        val out = ArrayList<PersonCentroid>()
        readableDatabase.rawQuery(
            "SELECT id, centroid, face_count FROM persons WHERE centroid IS NOT NULL", null
        ).use { c ->
            while (c.moveToNext()) out.add(PersonCentroid(c.getLong(0), c.getBlob(1), c.getInt(2)))
        }
        return out
    }

    fun updatePersonCentroid(personId: Long, centroid: ByteArray, faceCount: Int) {
        val values = ContentValues().apply {
            put("centroid", centroid)
            put("face_count", faceCount)
        }
        writableDatabase.update("persons", values, "id = ?", arrayOf(personId.toString()))
    }

    fun renamePerson(personId: Long, name: String) {
        val values = ContentValues().apply { put("name", name) }
        writableDatabase.update("persons", values, "id = ?", arrayOf(personId.toString()))
    }

    /**
     * Folds [loserId] into [winnerId]: crops are reassigned, face counts summed,
     * loser row deleted. Caller is responsible for recomputing the merged centroid
     * (via [updatePersonCentroid]) if it wants more than the winner's old one.
     */
    fun mergePersons(winnerId: Long, loserId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE crops SET person_id = ? WHERE person_id = ?",
                arrayOf(winnerId.toString(), loserId.toString())
            )
            db.execSQL(
                "UPDATE persons SET face_count = face_count + " +
                    "(SELECT face_count FROM persons WHERE id = ?) WHERE id = ?",
                arrayOf(loserId.toString(), winnerId.toString())
            )
            db.execSQL("DELETE FROM persons WHERE id = ?", arrayOf(loserId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

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
        const val DATABASE_VERSION = 3

        private const val CROP_SELECT =
            """
            SELECT c.id, c.media_id, p.content_uri, c.face_index, c.score, c.reason, c.crop_path,
                   c.tags, c.person_id
            FROM crops c JOIN photos p ON p.media_id = c.media_id
            """

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
