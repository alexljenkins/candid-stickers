package com.candidstickers.clip

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * CLIP BPE tokenizer backed by the HuggingFace `tokenizer.json` shipped in
 * `assets/clip/` (see docs/MODELS.md). Mirrors the original CLIP slow
 * tokenizer: NFC + whitespace collapse + lowercase, the CLIP split regex,
 * GPT-2 byte-to-unicode mapping, then ranked BPE merges where each word's
 * final symbol carries the `</w>` suffix.
 *
 * [encode] always returns exactly [CONTEXT_LENGTH] (77) ids laid out as
 * `[BOS, tokens..., EOS, 0-pad...]`, truncating long input at
 * [MAX_TEXT_TOKENS] (75) content tokens. The text encoder ONNX graph has a
 * fixed-size positional Add, so any other length crashes at inference time.
 *
 * Parsing the 2.2 MB JSON is done once per distinct file: the parsed
 * vocab/merges are cached statically, so constructing additional
 * [ClipTokenizer] instances from the same JSON is cheap.
 */
class ClipTokenizer(tokenizerJson: String) {

    private val model: Model = modelFor(tokenizerJson)

    /** Always length [CONTEXT_LENGTH]: BOS, content tokens, EOS, then 0 padding. */
    fun encode(text: String): LongArray {
        var clean = Normalizer.normalize(text, Normalizer.Form.NFC)
        clean = WHITESPACE.replace(clean, " ").trim().lowercase()

        val ids = ArrayList<Int>(32)
        for (match in SPLIT_PATTERN.findAll(clean)) {
            for (id in model.wordIds(match.value)) ids.add(id)
            if (ids.size >= MAX_TEXT_TOKENS) break
        }

        val count = minOf(ids.size, MAX_TEXT_TOKENS)
        val out = LongArray(CONTEXT_LENGTH) // trailing zeros are the pad id
        out[0] = model.bosId.toLong()
        for (i in 0 until count) out[i + 1] = ids[i].toLong()
        out[count + 1] = model.eosId.toLong()
        return out
    }

    /** Parsed vocab/merges plus a bounded per-word BPE cache. */
    private class Model(
        val vocab: Map<String, Int>,
        val mergeRanks: Map<String, Int>,
        val unkId: Int,
        val bosId: Int,
        val eosId: Int,
        val endOfWord: String,
    ) {
        private val wordCache = ConcurrentHashMap<String, IntArray>()

        fun wordIds(word: String): IntArray {
            wordCache[word]?.let { return it }
            val mapped = buildString(word.length) {
                for (b in word.toByteArray(Charsets.UTF_8)) {
                    append(BYTE_TO_UNICODE[b.toInt() and 0xFF])
                }
            }
            val pieces = bpe(mapped)
            val ids = IntArray(pieces.size) { i -> vocab[pieces[i]] ?: unkId }
            if (wordCache.size >= WORD_CACHE_LIMIT) wordCache.clear()
            wordCache[word] = ids
            return ids
        }

        /**
         * Reference CLIP merge loop: the last symbol starts in its
         * `</w>`-suffixed form, then the lowest-ranked adjacent pair is merged
         * (all its occurrences, left to right) until no ranked pair remains.
         */
        private fun bpe(mapped: String): List<String> {
            var word = ArrayList<String>(mapped.length)
            for (i in 0 until mapped.length - 1) word.add(mapped[i].toString())
            word.add(mapped[mapped.length - 1] + endOfWord)

            while (word.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestIndex = -1
                for (i in 0 until word.size - 1) {
                    val rank = mergeRanks[word[i] + " " + word[i + 1]] ?: continue
                    if (rank < bestRank) {
                        bestRank = rank
                        bestIndex = i
                    }
                }
                if (bestIndex < 0) break

                val first = word[bestIndex]
                val second = word[bestIndex + 1]
                val merged = ArrayList<String>(word.size - 1)
                var i = 0
                while (i < word.size) {
                    if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                        merged.add(first + second)
                        i += 2
                    } else {
                        merged.add(word[i])
                        i += 1
                    }
                }
                word = merged
            }
            return word
        }
    }

    companion object {
        /** Fixed sequence length the MobileCLIP text encoder requires. */
        const val CONTEXT_LENGTH = 77

        /** BOS + 75 content tokens + EOS = 77. */
        const val MAX_TEXT_TOKENS = CONTEXT_LENGTH - 2

        private const val BOS_TOKEN = "<|startoftext|>"
        private const val EOS_TOKEN = "<|endoftext|>"
        private const val WORD_CACHE_LIMIT = 8192

        private val WHITESPACE = Regex("""\s+""")

        /** CLIP's split regex: contractions, letter runs, single digits, other runs. */
        private val SPLIT_PATTERN = Regex(
            """'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE
        )

        /** GPT-2 byte-to-unicode table: every byte maps to a printable BMP char. */
        private val BYTE_TO_UNICODE: CharArray = run {
            val bytes = ArrayList<Int>(256)
            for (b in '!'.code..'~'.code) bytes.add(b)
            for (b in 0xA1..0xAC) bytes.add(b)
            for (b in 0xAE..0xFF) bytes.add(b)
            val chars = ArrayList<Int>(bytes)
            var next = 0
            for (b in 0..255) {
                if (b !in bytes) {
                    bytes.add(b)
                    chars.add(256 + next)
                    next++
                }
            }
            val table = CharArray(256)
            for (i in bytes.indices) table[bytes[i]] = chars[i].toChar()
            table
        }

        private val modelCache = ConcurrentHashMap<Long, Model>()

        private fun modelFor(json: String): Model =
            modelCache.getOrPut(cacheKey(json)) { parse(json) }

        private fun cacheKey(json: String): Long =
            (json.length.toLong() shl 32) xor (json.hashCode().toLong() and 0xFFFF_FFFFL)

        private fun parse(json: String): Model {
            val root = JSONObject(json)
            val modelObj = root.getJSONObject("model")

            val vocabObj = modelObj.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabObj.length() * 2)
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                vocab[key] = vocabObj.getInt(key)
            }
            // Specials usually live in vocab too, but added_tokens is authoritative.
            root.optJSONArray("added_tokens")?.let { added ->
                for (i in 0 until added.length()) {
                    val tok = added.getJSONObject(i)
                    vocab[tok.getString("content")] = tok.getInt("id")
                }
            }

            // Merges are "first second" strings in this file; newer tokenizers
            // exports use ["first","second"] pairs — accept both.
            val mergesArr = modelObj.getJSONArray("merges")
            val ranks = HashMap<String, Int>(mergesArr.length() * 2)
            for (i in 0 until mergesArr.length()) {
                val entry = mergesArr.get(i)
                val key = if (entry is JSONArray) {
                    "${entry.getString(0)} ${entry.getString(1)}"
                } else {
                    entry.toString()
                }
                ranks[key] = i
            }

            val bosId = vocab[BOS_TOKEN] ?: error("tokenizer.json vocab missing $BOS_TOKEN")
            val eosId = vocab[EOS_TOKEN] ?: error("tokenizer.json vocab missing $EOS_TOKEN")
            val unkId = vocab[modelObj.optString("unk_token", EOS_TOKEN)] ?: eosId
            val endOfWord = modelObj.optString("end_of_word_suffix", "</w>").ifEmpty { "</w>" }
            return Model(vocab, ranks, unkId, bosId, eosId, endOfWord)
        }
    }
}
