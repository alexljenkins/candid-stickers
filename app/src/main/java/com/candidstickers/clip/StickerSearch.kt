package com.candidstickers.clip

import android.content.Context
import com.candidstickers.data.CandidCrop
import com.candidstickers.data.CropDb
import com.candidstickers.data.FloatBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Free-text sticker search: embed the query with the CLIP text encoder, rank
 * every embedded crop by cosine similarity. A few thousand 512-d dot products
 * is instant; swap in sqlite-vec if the library ever grows past that.
 */
class StickerSearch(private val db: CropDb) {

    /**
     * Best-matching crops for [query], best first. Empty when the query is
     * blank or the CLIP encoder is unavailable (assets missing).
     */
    suspend fun search(context: Context, query: String, limit: Int = 100): List<CandidCrop> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val encoder = Clip.get(context) ?: return emptyList()
        val queryEmbedding = encoder.encodeText(query)
        val items = withContext(Dispatchers.IO) { db.cropEmbeddings() }
            .map { (id, blob) -> id to FloatBlob.toFloats(blob) }
        if (items.isEmpty()) return emptyList()
        val rankedIds = rank(queryEmbedding, items, limit)
        // cropsByIds preserves the caller's (ranked) order.
        return withContext(Dispatchers.IO) { db.cropsByIds(rankedIds) }
    }

    /**
     * Pure ranking: crop ids ordered by cosine similarity to [queryEmbedding],
     * best first, truncated to [limit]. Items whose embedding dimension does
     * not match the query are skipped (defensive against schema drift).
     */
    fun rank(queryEmbedding: FloatArray, items: List<Pair<Long, FloatArray>>, limit: Int): List<Long> {
        if (limit <= 0 || items.isEmpty()) return emptyList()
        return items
            .filter { (_, embedding) -> embedding.size == queryEmbedding.size && embedding.isNotEmpty() }
            .map { (id, embedding) -> id to VecMath.cosine(queryEmbedding, embedding) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
