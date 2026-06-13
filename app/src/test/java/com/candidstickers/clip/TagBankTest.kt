package com.candidstickers.clip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.candidstickers.data.CropDb
import com.candidstickers.data.FloatBlob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [TagBank.topTags] over fake unit-vector embeddings stored in `tag_bank`. */
@RunWith(AndroidJUnit4::class)
class TagBankTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CropDb

    @Before
    fun setUp() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
        db = CropDb.getInstance(context)
    }

    @After
    fun tearDown() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
    }

    private fun seedBank() {
        db.putTagBank("alpha", FloatBlob.toBytes(floatArrayOf(1f, 0f, 0f, 0f)))
        db.putTagBank("beta", FloatBlob.toBytes(floatArrayOf(0f, 1f, 0f, 0f)))
        db.putTagBank("diag", FloatBlob.toBytes(floatArrayOf(0.7071f, 0.7071f, 0f, 0f)))
    }

    @Test
    fun ordersByCosineDescending() {
        seedBank()
        // cos(diag)=0.98995, cos(beta)=0.8, cos(alpha)=0.6
        val tags = TagBank(db).topTags(floatArrayOf(0.6f, 0.8f, 0f, 0f))
        assertEquals(listOf("diag", "beta", "alpha"), tags)
    }

    @Test
    fun kLimitsResultCount() {
        seedBank()
        val bank = TagBank(db)
        val query = floatArrayOf(0.6f, 0.8f, 0f, 0f)
        assertEquals(listOf("diag"), bank.topTags(query, k = 1))
        assertEquals(listOf("diag", "beta"), bank.topTags(query, k = 2))
        assertEquals(emptyList<String>(), bank.topTags(query, k = 0))
    }

    @Test
    fun minCosCutsOffWeakMatches() {
        seedBank()
        val bank = TagBank(db)
        // cos(alpha)=1, cos(diag)=0.7071, cos(beta)=0 — default minCos drops beta.
        assertEquals(listOf("alpha", "diag"), bank.topTags(floatArrayOf(1f, 0f, 0f, 0f)))
        // raise the bar past diag
        assertEquals(
            listOf("alpha"),
            bank.topTags(floatArrayOf(1f, 0f, 0f, 0f), minCos = 0.8f)
        )
    }

    @Test
    fun mismatchedDimensionTagIsSkippedNotFatal() {
        seedBank()
        db.putTagBank("broken", FloatBlob.toBytes(floatArrayOf(1f, 0f)))
        val tags = TagBank(db).topTags(floatArrayOf(1f, 0f, 0f, 0f), k = 10)
        assertEquals(listOf("alpha", "diag"), tags)
    }

    @Test
    fun emptyBankYieldsNoTags() {
        assertEquals(emptyList<String>(), TagBank(db).topTags(floatArrayOf(1f, 0f, 0f, 0f)))
    }

    @Test
    fun topTagsReadsBankWithoutEnsureReady() {
        // ensureReady needs a real encoder; the lazy DB fallback must suffice.
        seedBank()
        assertEquals(listOf("alpha"), TagBank(db).topTags(floatArrayOf(1f, 0f, 0f, 0f), k = 1))
    }

    @Test
    fun curatedVocabularyIsWellFormed() {
        val labels = TagBank.PHRASES.map { it.label }
        assertTrue("want a rich vocabulary, got ${labels.size}", labels.size >= 40)
        assertEquals("labels must be unique", labels.size, labels.toSet().size)
        for (phrase in TagBank.PHRASES) {
            assertTrue("blank label", phrase.label.isNotBlank())
            assertTrue("prompt should be descriptive: ${phrase.label}", phrase.prompt.length > phrase.label.length)
        }
    }
}
