package com.candidstickers.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CandidCrop(
    val id: Long,
    val mediaId: Long,
    val contentUri: String,
    val faceIndex: Int,
    val score: Float,
    val reason: String,
    val cropPath: String,
)

/**
 * Plain SQLite (not Room) so we can swap in a sqlite-vec-enabled build later
 * without fighting an ORM. `crops.embedding` is reserved for milestone 3.
 */
class CropDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "candid.db", null, 1) {

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
                embedding  BLOB
            )
            """
        )
        db.execSQL("CREATE INDEX idx_crops_score ON crops(score DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
            while (c.moveToNext()) {
                out.add(
                    CandidCrop(
                        id = c.getLong(0),
                        mediaId = c.getLong(1),
                        contentUri = c.getString(2),
                        faceIndex = c.getInt(3),
                        score = c.getFloat(4),
                        reason = c.getString(5),
                        cropPath = c.getString(6),
                    )
                )
            }
        }
        return out
    }
}
