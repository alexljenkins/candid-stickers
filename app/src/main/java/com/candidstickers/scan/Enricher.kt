package com.candidstickers.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.candidstickers.clip.Clip
import com.candidstickers.clip.ClipEncoder
import com.candidstickers.clip.TagBank
import com.candidstickers.data.CandidCrop
import com.candidstickers.data.CropDb
import com.candidstickers.data.FloatBlob
import com.candidstickers.people.PersonClusterer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * Post-detection enrichment: CLIP-embeds + tags each crop (search/tag chips)
 * and aligns + face-embeds + person-clusters it (People tab). Runs inline as
 * the scan persists each crop ([enrich]) and as a catch-up pass over crops
 * that predate the models or were skipped earlier ([backfill]).
 *
 * Every step degrades independently: a missing CLIP encoder skips silently
 * (the next backfill catches up once the models are fetched), while faces that
 * can never be embedded — too small to align, source photo gone — are marked
 * with a no-retry sentinel so the backfill doesn't grind on them forever.
 */
class Enricher(private val context: Context, private val db: CropDb) {

    data class Progress(val done: Int, val total: Int)

    /**
     * Enriches one freshly persisted crop. [cropBitmap] is the sticker bitmap
     * just written to disk (CLIP input); [fullBitmap] the decoded source photo
     * [keypoints] live in (face alignment warps from full resolution).
     *
     * @return [crop] updated with whatever tags/person the pass produced.
     */
    suspend fun enrich(
        crop: CandidCrop,
        cropBitmap: Bitmap,
        fullBitmap: Bitmap,
        keypoints: FaceAnalyzer.Keypoints?,
        clusterer: PersonClusterer,
        embedder: FaceEmbedder?,
        tagBank: TagBank,
    ): CandidCrop {
        var enriched = crop

        Clip.get(context)?.let { encoder ->
            try {
                enriched = enriched.copy(tags = clipTag(encoder, crop.id, cropBitmap, tagBank))
            } catch (e: Exception) {
                Log.w(TAG, "CLIP enrichment failed for crop ${crop.id}", e)
            }
        }

        if (keypoints != null && embedder != null) {
            try {
                faceCluster(crop.id, fullBitmap, keypoints, clusterer, embedder)?.let {
                    enriched = enriched.copy(personId = it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Face enrichment failed for crop ${crop.id}", e)
            }
        }
        return enriched
    }

    /**
     * Catch-up pass over crops missing CLIP or face data (created before the
     * models existed, or while the CLIP assets were absent). Safe to run after
     * every scan: when nothing is missing it costs two SELECTs.
     */
    suspend fun backfill(onProgress: suspend (Progress) -> Unit = {}) = withContext(Dispatchers.Default) {
        val encoder = Clip.get(context)
        val clipTodo =
            if (encoder == null) emptyList()
            else withContext(Dispatchers.IO) { db.cropsMissingClip(limit = Int.MAX_VALUE) }
        val faceTodo = withContext(Dispatchers.IO) { db.cropsMissingFace(limit = Int.MAX_VALUE) }
        if (clipTodo.isEmpty() && faceTodo.isEmpty()) return@withContext

        val total = clipTodo.size + faceTodo.size
        var done = 0

        if (encoder != null && clipTodo.isNotEmpty()) {
            val tagBank = TagBank(db)
            tagBank.ensureReady(encoder)
            for (crop in clipTodo) {
                coroutineContext.ensureActive()
                try {
                    backfillClip(encoder, crop, tagBank)
                } catch (e: Exception) {
                    Log.w(TAG, "CLIP backfill failed for crop ${crop.id}", e)
                }
                onProgress(Progress(++done, total))
            }
        }

        if (faceTodo.isNotEmpty()) {
            val embedder = try {
                FaceEmbedder(context)
            } catch (e: Exception) {
                Log.w(TAG, "FaceEmbedder init failed; skipping face backfill", e)
                null
            }
            if (embedder == null) {
                onProgress(Progress(total, total))
                return@withContext
            }
            embedder.use {
                FaceAnalyzer(context).use { analyzer ->
                    val clusterer = PersonClusterer(db)
                    // One decode + landmark pass per source photo, however many crops it spawned.
                    for ((uriString, crops) in faceTodo.groupBy { it.contentUri }) {
                        coroutineContext.ensureActive()
                        val photo = PhotoDecoder.decode(context, Uri.parse(uriString))
                        try {
                            val faces =
                                if (photo != null && analyzer.hasFaces(photo)) analyzer.analyze(photo)
                                else emptyList()
                            for (crop in crops) {
                                try {
                                    val keypoints = faces.getOrNull(crop.faceIndex)?.keypoints
                                    if (photo == null || keypoints == null) {
                                        // Photo gone or face no longer detected — never retry.
                                        withContext(Dispatchers.IO) { db.markCropFaceNone(crop.id) }
                                    } else {
                                        faceCluster(crop.id, photo, keypoints, clusterer, embedder)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Face backfill failed for crop ${crop.id}", e)
                                }
                                onProgress(Progress(++done, total))
                            }
                        } finally {
                            photo?.recycle()
                        }
                    }
                    runCatching { clusterer.compact() }
                        .onFailure { Log.w(TAG, "Person compaction failed", it) }
                }
            }
        }
    }

    private suspend fun backfillClip(encoder: ClipEncoder, crop: CandidCrop, tagBank: TagBank) {
        val bitmap = BitmapFactory.decodeFile(crop.cropPath)
        if (bitmap == null) {
            // Crop PNG gone/corrupt — empty-blob sentinel, never retry.
            withContext(Dispatchers.IO) { db.updateCropClip(crop.id, ByteArray(0), "[]") }
            return
        }
        try {
            clipTag(encoder, crop.id, bitmap, tagBank)
        } finally {
            bitmap.recycle()
        }
    }

    /** Embeds [cropBitmap], persists embedding + tags, returns the tag labels. */
    private suspend fun clipTag(
        encoder: ClipEncoder,
        cropId: Long,
        cropBitmap: Bitmap,
        tagBank: TagBank,
    ): List<String> {
        val embedding = encoder.encodeImage(cropBitmap)
        val tags = tagBank.topTags(embedding)
        withContext(Dispatchers.IO) {
            db.updateCropClip(cropId, FloatBlob.toBytes(embedding), JSONArray(tags).toString())
        }
        return tags
    }

    /**
     * Aligns the face out of [fullBitmap], embeds it, and assigns it to a
     * person. An unalignable face (too small, degenerate keypoints) gets the
     * no-retry sentinel and returns null.
     */
    private suspend fun faceCluster(
        cropId: Long,
        fullBitmap: Bitmap,
        keypoints: FaceAnalyzer.Keypoints,
        clusterer: PersonClusterer,
        embedder: FaceEmbedder,
    ): Long? {
        val aligned = FaceAligner.align(fullBitmap, keypoints)
        if (aligned == null) {
            withContext(Dispatchers.IO) { db.markCropFaceNone(cropId) }
            return null
        }
        val embedding = try {
            embedder.embed(aligned)
        } finally {
            aligned.recycle()
        }
        return withContext(Dispatchers.IO) {
            val personId = clusterer.assign(embedding)
            db.updateCropFace(cropId, personId, FloatBlob.toBytes(embedding))
            personId
        }
    }

    private companion object {
        const val TAG = "Enricher"
    }
}
