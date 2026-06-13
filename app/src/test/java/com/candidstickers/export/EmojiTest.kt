package com.candidstickers.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiTest {

    private val reasons = listOf(
        "eyes shut", "mid-sneeze", "jaw drop", "shocked", "cheek puff", "duck face",
        "brow raise", "scowl", "sneer", "grimace", "gasp", "wink", "squint",
    )

    @Test
    fun everyMemeScorerReasonMapsToOneToThreeEmoji() {
        for (reason in reasons) {
            val emoji = Emoji.forReason(reason)
            assertTrue(reason, emoji.size in 1..3)
            assertTrue(reason, emoji.none { it.isBlank() })
        }
    }

    @Test
    fun unknownReasonFallsBackToDefault() {
        assertEquals(listOf("😂"), Emoji.forReason("interpretive dance"))
        assertEquals(Emoji.DEFAULT, Emoji.forReason(""))
    }

    @Test
    fun packIdentifierIsStableAndSatisfiesWhatsAppRules() {
        assertEquals("candid-7", Emoji.packIdentifier(7))
        assertEquals("candid-7", Emoji.packIdentifier(7)) // stable
        val id = Emoji.packIdentifier(123_456_789L)
        assertTrue(id.matches(Regex("[-\\w.,' ]+")))
        assertFalse(id.contains(".."))
        assertTrue(id.length <= 128)
    }
}
