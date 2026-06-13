package com.candidstickers.export

import android.content.Context
import android.graphics.BitmapFactory
import com.candidstickers.data.CropDb
import com.candidstickers.data.PackRow
import com.candidstickers.data.PackStickerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Owns sticker-pack state: WebP/tray files under `filesDir/packs/<identifier>/`
 * plus the packs/pack_stickers rows. Every change to an existing pack's
 * stickers or tray bumps `image_data_version` — WhatsApp's only cache-refresh
 * signal.
 */
class PackManager(context: Context) {

    private val appContext = context.applicationContext
    private val db = CropDb.getInstance(appContext)

    sealed class PackError(message: String) : Exception(message) {
        class TooFewStickers :
            PackError("A pack needs at least $MIN_STICKERS stickers — delete the pack instead of dropping below that")
        class TooManyStickers : PackError("A pack can hold at most $MAX_STICKERS stickers")
        class EncodeFailed(fileName: String) :
            PackError("Could not encode $fileName within WhatsApp's size limits")
        class CropsMissing : PackError("Some selected crops no longer exist")
        class PackMissing : PackError("Pack no longer exists")
    }

    suspend fun createPack(name: String, cropIds: List<Long>): Result<PackRow> =
        withContext(Dispatchers.IO) {
            val ids = cropIds.distinct()
            if (ids.size < MIN_STICKERS) return@withContext Result.failure(PackError.TooFewStickers())
            if (ids.size > MAX_STICKERS) return@withContext Result.failure(PackError.TooManyStickers())
            val crops = db.cropsByIds(ids)
            if (crops.size != ids.size) return@withContext Result.failure(PackError.CropsMissing())

            val packId = db.insertPack(
                "candid-pending-${UUID.randomUUID()}", name, PUBLISHER, System.currentTimeMillis()
            )
            if (packId == -1L) return@withContext Result.failure(PackError.PackMissing())
            // The row id isn't known before the insert; AUTOINCREMENT never
            // reuses ids, so candid-<id> stays unique forever.
            val identifier = Emoji.packIdentifier(packId)
            db.updatePackIdentifier(packId, identifier)

            val dir = packDir(identifier).apply { mkdirs() }
            for (crop in crops) {
                val fileName = stickerFileName(crop.id)
                if (!renderSticker(crop.cropPath, File(dir, fileName))) {
                    db.deletePack(packId)
                    dir.deleteRecursively()
                    return@withContext Result.failure(PackError.EncodeFailed(fileName))
                }
            }
            crops.forEachIndexed { position, crop ->
                db.insertPackSticker(
                    packId, crop.id, stickerFileName(crop.id),
                    Emoji.forReason(crop.reason).joinToString(","), position
                )
            }
            if (!renderTray(dir, db.packStickers(packId).first())) {
                db.deletePack(packId)
                dir.deleteRecursively()
                return@withContext Result.failure(PackError.EncodeFailed(TRAY_FILE_NAME))
            }
            db.updatePackTray(packId, TRAY_FILE_NAME)
            Result.success(db.pack(packId)!!)
        }

    suspend fun addToPack(packId: Long, cropIds: List<Long>): Result<PackRow> =
        withContext(Dispatchers.IO) {
            val pack = db.pack(packId) ?: return@withContext Result.failure(PackError.PackMissing())
            val existing = db.packStickers(packId)
            val existingIds = existing.mapTo(HashSet()) { it.cropId }
            val newIds = cropIds.distinct().filterNot { it in existingIds }
            if (newIds.isEmpty()) return@withContext Result.success(pack)
            if (existing.size + newIds.size > MAX_STICKERS) {
                return@withContext Result.failure(PackError.TooManyStickers())
            }
            val crops = db.cropsByIds(newIds)
            if (crops.size != newIds.size) return@withContext Result.failure(PackError.CropsMissing())

            val dir = packDir(pack.identifier).apply { mkdirs() }
            val rendered = ArrayList<File>()
            for (crop in crops) {
                val file = File(dir, stickerFileName(crop.id))
                if (!renderSticker(crop.cropPath, file)) {
                    rendered.forEach { it.delete() }
                    return@withContext Result.failure(PackError.EncodeFailed(file.name))
                }
                rendered.add(file)
            }
            var position = (existing.maxOfOrNull { it.position } ?: -1) + 1
            for (crop in crops) {
                db.insertPackSticker(
                    packId, crop.id, stickerFileName(crop.id),
                    Emoji.forReason(crop.reason).joinToString(","), position++
                )
            }
            if (pack.trayFile.isEmpty() || !File(dir, TRAY_FILE_NAME).isFile) {
                db.packStickers(packId).firstOrNull()?.let { first ->
                    if (renderTray(dir, first)) db.updatePackTray(packId, TRAY_FILE_NAME)
                }
            }
            db.bumpImageDataVersion(packId)
            Result.success(db.pack(packId)!!)
        }

    suspend fun removeSticker(packId: Long, cropId: Long): Result<PackRow> =
        withContext(Dispatchers.IO) {
            val pack = db.pack(packId) ?: return@withContext Result.failure(PackError.PackMissing())
            val stickers = db.packStickers(packId)
            val target = stickers.find { it.cropId == cropId }
                ?: return@withContext Result.success(pack)
            if (stickers.size - 1 < MIN_STICKERS) {
                return@withContext Result.failure(PackError.TooFewStickers())
            }

            db.removePackSticker(packId, cropId)
            val dir = packDir(pack.identifier)
            File(dir, target.fileName).delete()
            if (target.cropId == stickers.first().cropId) {
                // Removed the tray source — regenerate from the new first sticker.
                db.packStickers(packId).firstOrNull()?.let { renderTray(dir, it) }
            }
            db.bumpImageDataVersion(packId)
            Result.success(db.pack(packId)!!)
        }

    suspend fun deletePack(packId: Long) {
        withContext(Dispatchers.IO) {
            val pack = db.pack(packId) ?: return@withContext
            db.deletePack(packId)
            packDir(pack.identifier).deleteRecursively()
        }
    }

    fun packs(): List<PackRow> = db.packs()

    fun stickers(packId: Long): List<PackStickerRow> = db.packStickers(packId)

    fun packDir(identifier: String): File = packDir(appContext, identifier)

    private fun renderSticker(cropPath: String, dest: File): Boolean {
        val src = BitmapFactory.decodeFile(cropPath) ?: return false
        return try {
            renderStickerWebp(src, dest)
        } finally {
            src.recycle()
        }
    }

    private fun renderTray(dir: File, first: PackStickerRow): Boolean {
        val src = BitmapFactory.decodeFile(first.cropPath)
            ?: BitmapFactory.decodeFile(File(dir, first.fileName).path)
            ?: return false
        return try {
            renderTrayPng(src, File(dir, TRAY_FILE_NAME))
        } finally {
            src.recycle()
        }
    }

    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
        const val TRAY_FILE_NAME = "tray.png"
        const val PUBLISHER = "Candid Stickers"

        /** Single source of truth for the pack file layout (provider reads it too). */
        fun packDir(context: Context, identifier: String): File =
            File(File(context.filesDir, "packs"), identifier)

        private fun stickerFileName(cropId: Long) = "$cropId.webp"
    }
}
