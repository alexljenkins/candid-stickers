package com.candidstickers.clip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Against the real `assets/clip/tokenizer.json`. Golden id sequences were
 * generated with a reference implementation of the CLIP slow tokenizer over
 * this exact file (BOS 49406, EOS 49407, pad 0, context 77).
 */
@RunWith(AndroidJUnit4::class)
class TokenizerTest {

    private val tokenizer: ClipTokenizer
        get() = sharedTokenizer

    @Test
    fun goldenCryingLaughing() {
        assertArrayEquals(padded(BOS, 6828, 8301, EOS), tokenizer.encode("crying laughing"))
    }

    @Test
    fun lowercasesAndCollapsesWhitespace() {
        assertArrayEquals(tokenizer.encode("crying laughing"), tokenizer.encode("CRYING \t\n Laughing"))
        assertArrayEquals(tokenizer.encode("crying laughing"), tokenizer.encode("  Crying   LAUGHING  "))
    }

    @Test
    fun punctuationSplitsIntoOwnTokens() {
        // "," -> 267, "!" -> 256 in this vocab.
        assertArrayEquals(padded(BOS, 6828, 267, 8301, 256, EOS), tokenizer.encode("crying, laughing!"))
    }

    @Test
    fun apostropheContractionUsesSpecialAlternative() {
        // "don" 847, "'t" 713, "blink" 18976.
        assertArrayEquals(padded(BOS, 847, 713, 18976, EOS), tokenizer.encode("don't blink"))
    }

    @Test
    fun photoTemplateGolden() {
        assertArrayEquals(
            padded(BOS, 320, 1125, 539, 320, 6910, 1710, EOS),
            tokenizer.encode("a photo of a duck face")
        )
    }

    @Test
    fun emojiEncodesViaByteFallbackMerges() {
        // U+1F602 FACE WITH TEARS OF JOY merges to a single learned token.
        assertArrayEquals(padded(BOS, 1558, EOS), tokenizer.encode("😂"))
    }

    @Test
    fun nfcNormalizesCombiningMarks() {
        val composed = "café"          // café, precomposed
        val decomposed = "cafe\u0301"      // cafe + combining acute accent
        assertArrayEquals(padded(BOS, 15304, EOS), tokenizer.encode(composed))
        assertArrayEquals(tokenizer.encode(composed), tokenizer.encode(decomposed))
    }

    @Test
    fun emptyAndBlankInputAreBosEosOnly() {
        assertArrayEquals(padded(BOS, EOS), tokenizer.encode(""))
        assertArrayEquals(padded(BOS, EOS), tokenizer.encode("   \t\n "))
    }

    @Test
    fun longInputTruncatesAt75TokensWithTrailingEos() {
        val ids = tokenizer.encode(List(100) { "crying" }.joinToString(" "))
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, ids.size)
        assertEquals(BOS, ids[0])
        assertEquals(6828L, ids[1])
        assertEquals(6828L, ids[75])
        assertEquals(EOS, ids[76])
        assertTrue("truncated sequence has no padding", ids.none { it == 0L })
    }

    @Test
    fun outputLengthIsAlways77() {
        val inputs = listOf(
            "", " ", "a", "hi!", "crying laughing", "ñ café 😂😂😂 123",
            "line\nbreaks\tand tabs", "!!!???...", "1234567890",
            List(300) { "word$it" }.joinToString(" "),
        )
        for (input in inputs) {
            val ids = tokenizer.encode(input)
            assertEquals("length for $input", ClipTokenizer.CONTEXT_LENGTH, ids.size)
            assertEquals("BOS for $input", BOS, ids[0])
            assertTrue("EOS present for $input", ids.contains(EOS))
        }
    }

    @Test
    fun digitsTokenizeIndividually() {
        // CLIP's regex matches single digits: "12" -> "1</w>"=272, "2</w>"=273.
        assertArrayEquals(padded(BOS, 272, 273, EOS), tokenizer.encode("12"))
    }

    @Test
    fun secondConstructionFromSameJsonBehavesIdentically() {
        // The parsed vocab/merges are cached statically; a fresh instance from
        // the same JSON must agree with the shared one.
        val again = ClipTokenizer(tokenizerJson)
        assertArrayEquals(tokenizer.encode("crying laughing"), again.encode("crying laughing"))
    }

    private fun padded(vararg ids: Long): LongArray =
        LongArray(ClipTokenizer.CONTEXT_LENGTH).also { out ->
            ids.forEachIndexed { i, v -> out[i] = v }
        }

    companion object {
        private const val BOS = 49406L
        private const val EOS = 49407L

        private val tokenizerJson: String by lazy {
            ApplicationProvider.getApplicationContext<Context>()
                .assets.open("clip/tokenizer.json")
                .bufferedReader()
                .use { it.readText() }
        }

        private val sharedTokenizer: ClipTokenizer by lazy { ClipTokenizer(tokenizerJson) }
    }
}
