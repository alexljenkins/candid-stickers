package com.candidstickers.scan

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.candidstickers.clip.Clip
import com.candidstickers.clip.TagBank
import com.candidstickers.data.CandidCrop
import com.candidstickers.data.CropDb
import com.candidstickers.people.PersonClusterer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The candid miner: walk MediaStore (newest first), detect faces, score
 * meme-ability, matte the keepers, persist crops, then enrich each crop
 * (CLIP tags + face clustering) via [Enricher] while the photo bitmap is
 * still in hand.
 */
class ScanPipeline(private val context: Context, private val db: CropDb) {

    data class Progress(val scanned: Int, val total: Int, val cropsFound: Int)

    suspend fun scan(
        onProgress: suspend (Progress) -> Unit = {},
        onCrop: suspend (CandidCrop) -> Unit = {},
    ): Int = withContext(Dispatchers.Default) {
        val photos = queryUnscannedPhotos()
        if (photos.isEmpty()) return@withContext 0

        val cropsDir = File(context.filesDir, "crops").apply { mkdirs() }
        var scanned = 0
        var found = 0

        FaceAnalyzer(context).use { analyzer ->
            val matte = SubjectMatte()
            val embedder = try {
                FaceEmbedder(context)
            } catch (e: Exception) {
                Log.w(TAG, "FaceEmbedder init failed; scanning without face clustering", e)
                null
            }
            val enrichment = EnrichmentPass(
                enricher = Enricher(context, db),
                clusterer = PersonClusterer(db),
                embedder = embedder,
                tagBank = TagBank(db),
            )
            try {
                // Embed the tag vocabulary once per scan (no-op when already cached).
                Clip.get(context)?.let { encoder ->
                    runCatching { enrichment.tagBank.ensureReady(encoder) }
                        .onFailure { Log.w(TAG, "Tag bank init failed; tagging degraded", it) }
                }
                for (photo in photos) {
                    coroutineContext.ensureActive()
                    try {
                        found += scanOne(photo, analyzer, matte, cropsDir, enrichment, onCrop)
                    } catch (e: Exception) {
                        // Bad/corrupt image or decoder hiccup — mark scanned, move on.
                        db.markScanned(photo.mediaId, photo.uri.toString(), photo.dateTaken, 0)
                    }
                    scanned++
                    onProgress(Progress(scanned, photos.size, found))
                }
                runCatching { enrichment.clusterer.compact() }
                    .onFailure { Log.w(TAG, "Person compaction failed", it) }
            } finally {
                matte.close()
                embedder?.close()
            }
        }
        found
    }

    /** Per-scan-run enrichment collaborators: built once, shared by every [scanOne]. */
    private class EnrichmentPass(
        val enricher: Enricher,
        val clusterer: PersonClusterer,
        val embedder: FaceEmbedder?,
        val tagBank: TagBank,
    )

    private suspend fun scanOne(
        photo: PhotoRef,
        analyzer: FaceAnalyzer,
        matte: SubjectMatte,
        cropsDir: File,
        enrichment: EnrichmentPass,
        onCrop: suspend (CandidCrop) -> Unit,
    ): Int {
        val bitmap = PhotoDecoder.decode(context, photo.uri) ?: run {
            db.markScanned(photo.mediaId, photo.uri.toString(), photo.dateTaken, 0)
            return 0
        }

        val faces = if (analyzer.hasFaces(bitmap)) analyzer.analyze(bitmap) else emptyList()
        var found = 0
        faces.forEachIndexed { index, face ->
            val (score, reason) = MemeScorer.score(face.blendshapes)
            if (score < MemeScorer.KEEP_THRESHOLD) return@forEachIndexed

            val sticker = renderSticker(bitmap, face.box, matte)
            val file = File(cropsDir, "${photo.mediaId}_$index.png")
            file.outputStream().use { sticker.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val id = db.insertCrop(photo.mediaId, index, score, reason, file.absolutePath)
            val crop = enrichment.enricher.enrich(
                crop = CandidCrop(
                    id = id,
                    mediaId = photo.mediaId,
                    contentUri = photo.uri.toString(),
                    faceIndex = index,
                    score = score,
                    reason = reason,
                    cropPath = file.absolutePath,
                ),
                cropBitmap = sticker,
                fullBitmap = bitmap,
                keypoints = face.keypoints,
                clusterer = enrichment.clusterer,
                embedder = enrichment.embedder,
                tagBank = enrichment.tagBank,
            )
            if (sticker !== bitmap) sticker.recycle()
            onCrop(crop)
            found++
        }
        db.markScanned(photo.mediaId, photo.uri.toString(), photo.dateTaken, faces.size)
        bitmap.recycle()
        return found
    }

    /**
     * Expand the face box to head-and-shoulders, lay the matted subject (when
     * available) over transparency, and downscale to sticker size.
     */
    private suspend fun renderSticker(bitmap: Bitmap, faceBox: RectF, matte: SubjectMatte): Bitmap {
        val pad = 0.45f * max(faceBox.width(), faceBox.height())
        val region = Rect(
            (faceBox.left - pad).roundToInt().coerceAtLeast(0),
            (faceBox.top - pad * 1.3f).roundToInt().coerceAtLeast(0), // extra headroom for hair
            (faceBox.right + pad).roundToInt().coerceAtMost(bitmap.width),
            (faceBox.bottom + pad).roundToInt().coerceAtMost(bitmap.height),
        )

        val out = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matted = matte.matte(bitmap, faceBox)
        if (matted != null) {
            canvas.drawBitmap(
                matted.bitmap,
                (matted.offsetX - region.left).toFloat(),
                (matted.offsetY - region.top).toFloat(),
                null
            )
            matted.bitmap.recycle()
        } else {
            canvas.drawBitmap(bitmap, -region.left.toFloat(), -region.top.toFloat(), null)
        }

        val maxDim = max(out.width, out.height)
        if (maxDim <= STICKER_SIZE) return out
        val scale = STICKER_SIZE.toFloat() / maxDim
        val scaled = Bitmap.createScaledBitmap(
            out, (out.width * scale).roundToInt(), (out.height * scale).roundToInt(), true
        )
        out.recycle()
        return scaled
    }

    private data class PhotoRef(val mediaId: Long, val uri: Uri, val dateTaken: Long)

    private fun queryUnscannedPhotos(): List<PhotoRef> {
        val seen = db.scannedMediaIds()
        val photos = ArrayList<PhotoRef>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                if (id in seen) continue
                photos.add(
                    PhotoRef(
                        mediaId = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        dateTaken = c.getLong(dateCol),
                    )
                )
            }
        }
        return photos
    }

    companion object {
        private const val TAG = "ScanPipeline"
        private const val STICKER_SIZE = 512
    }
}
