package com.candidstickers.clip

import com.candidstickers.data.CropDb
import com.candidstickers.data.FloatBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Curated candid/meme tag vocabulary scored against CLIP image embeddings.
 *
 * Each entry stores a SHORT display label (what lands in `crops.tags` and the
 * UI) while the embedding is computed from a longer templated prompt CLIP
 * understands better. Embeddings are cached in the `tag_bank` table so the
 * text encoder runs once per phrase per install.
 */
class TagBank(private val db: CropDb) {

    /** Label paired with its decoded embedding, held in memory after first load. */
    private class CachedTag(val label: String, val embedding: FloatArray)

    @Volatile
    private var cache: List<CachedTag> = emptyList()

    /**
     * Embeds any curated phrase missing from `tag_bank` (via
     * [CropDb.putTagBank]) and loads the bank into memory. Call once before a
     * tagging pass; safe to call repeatedly.
     */
    suspend fun ensureReady(encoder: ClipEncoder) {
        val stored = withContext(Dispatchers.IO) { db.tagBank() }
        for (phrase in PHRASES) {
            if (phrase.label in stored) continue
            val embedding = encoder.encodeText(phrase.prompt)
            withContext(Dispatchers.IO) {
                db.putTagBank(phrase.label, FloatBlob.toBytes(embedding))
            }
        }
        cache = withContext(Dispatchers.IO) { readBank() }
    }

    /**
     * Top-[k] tag labels whose cosine similarity with [imageEmbedding] clears
     * [minCos], best first. Falls back to a synchronous `tag_bank` read when
     * [ensureReady] has not populated the cache yet — call from
     * Dispatchers.IO/Default, not the main thread.
     */
    fun topTags(imageEmbedding: FloatArray, k: Int = 3, minCos: Float = 0.18f): List<String> {
        if (k <= 0) return emptyList()
        var bank = cache
        if (bank.isEmpty()) {
            bank = readBank()
            cache = bank
        }
        return bank
            .mapNotNull { tag ->
                val cos = VecMath.cosine(imageEmbedding, tag.embedding)
                if (cos >= minCos) tag.label to cos else null
            }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    private fun readBank(): List<CachedTag> =
        db.tagBank().map { (label, blob) -> CachedTag(label, FloatBlob.toFloats(blob)) }

    /** A short stored/displayed [label] and the templated [prompt] CLIP embeds. */
    data class TagPhrase(val label: String, val prompt: String)

    companion object {
        /** ~48 phrases tuned for candid camera-roll outtakes. */
        internal val PHRASES: List<TagPhrase> = listOf(
            TagPhrase("crying laughing", "a photo of a person laughing so hard they are crying"),
            TagPhrase("laughing", "a photo of a person laughing out loud"),
            TagPhrase("big smile", "a photo of a person with a big toothy smile"),
            TagPhrase("awkward smile", "a photo of an awkward forced smile"),
            TagPhrase("smirk", "a photo of a person smirking"),
            TagPhrase("evil grin", "a photo of a person with an evil grin"),
            TagPhrase("eyes closed", "a photo of a person with eyes closed mid blink"),
            TagPhrase("winking", "a photo of a person winking one eye"),
            TagPhrase("side eye", "a photo of a person giving a side eye glance"),
            TagPhrase("eye roll", "a photo of a person rolling their eyes"),
            TagPhrase("crazy eyes", "a photo of a person with wide crazy eyes"),
            TagPhrase("squinting", "a photo of a person squinting in bright light"),
            TagPhrase("raised eyebrow", "a photo of a person raising one eyebrow skeptically"),
            TagPhrase("shocked", "a photo of a shocked face with mouth wide open"),
            TagPhrase("surprised", "a photo of a surprised face with raised eyebrows"),
            TagPhrase("confused", "a photo of a confused person squinting at something"),
            TagPhrase("thinking", "a photo of a person with a thoughtful thinking face"),
            TagPhrase("unimpressed", "a photo of an unimpressed deadpan face"),
            TagPhrase("judging", "a photo of a person with a judgmental disapproving look"),
            TagPhrase("dead inside", "a photo of a person with a blank dead inside stare"),
            TagPhrase("disgusted", "a photo of a disgusted face"),
            TagPhrase("grimace", "a photo of a person grimacing"),
            TagPhrase("nose scrunch", "a photo of a person scrunching their nose"),
            TagPhrase("angry", "a photo of an angry scowling face"),
            TagPhrase("pouting", "a photo of a sulky pouting face"),
            TagPhrase("duck face", "a photo of a person making a duck face pout"),
            TagPhrase("kissy face", "a photo of a person making a kissy face"),
            TagPhrase("blowing kiss", "a photo of a person blowing a kiss"),
            TagPhrase("tongue out", "a photo of a person sticking their tongue out"),
            TagPhrase("silly face", "a photo of a person making a silly face"),
            TagPhrase("crying", "a photo of a person crying"),
            TagPhrase("ugly crying", "a photo of a person ugly crying with a scrunched face"),
            TagPhrase("screaming", "a photo of a person screaming"),
            TagPhrase("yelling", "a photo of a person yelling mid argument"),
            TagPhrase("yawning", "a photo of a person yawning"),
            TagPhrase("sleepy", "a photo of a sleepy tired face"),
            TagPhrase("sneezing", "a photo of a person sneezing"),
            TagPhrase("mid sentence", "a photo of a person caught talking mid sentence"),
            TagPhrase("singing", "a photo of a person singing passionately"),
            TagPhrase("eating", "a photo of a person stuffing food into their mouth"),
            TagPhrase("mid bite", "a photo of a person caught mid bite of food"),
            TagPhrase("drinking", "a photo of a person drinking from a glass"),
            TagPhrase("double chin", "a photo of a person making a double chin face"),
            TagPhrase("facepalm", "a photo of a person facepalming in their hand"),
            TagPhrase("thumbs up", "a photo of a person giving a thumbs up"),
            TagPhrase("peace sign", "a photo of a person making a peace sign"),
            TagPhrase("dancing", "a photo of a person dancing"),
            TagPhrase("photobomb", "a photo of a person photobombing in the background"),
        )
    }
}
