package com.candidstickers.clip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.candidstickers.data.CropDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pure [StickerSearch.rank] behavior plus the cheap [StickerSearch.search] guards. */
@RunWith(AndroidJUnit4::class)
class StickerSearchRankTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var search: StickerSearch

    @Before
    fun setUp() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
        search = StickerSearch(CropDb.getInstance(context))
    }

    @After
    fun tearDown() {
        CropDb.closeAndResetForTesting()
        context.deleteDatabase(CropDb.DATABASE_NAME)
    }

    private val query = floatArrayOf(1f, 0f)

    @Test
    fun ranksByCosineDescending() {
        val items = listOf(
            1L to floatArrayOf(0f, 1f),            // cos 0
            2L to floatArrayOf(1f, 0f),            // cos 1
            3L to floatArrayOf(0.7071f, 0.7071f),  // cos 0.7071
        )
        assertEquals(listOf(2L, 3L, 1L), search.rank(query, items, limit = 10))
    }

    @Test
    fun respectsLimit() {
        val items = listOf(
            1L to floatArrayOf(0f, 1f),
            2L to floatArrayOf(1f, 0f),
            3L to floatArrayOf(0.7071f, 0.7071f),
        )
        assertEquals(listOf(2L, 3L), search.rank(query, items, limit = 2))
        assertEquals(emptyList<Long>(), search.rank(query, items, limit = 0))
        assertEquals(emptyList<Long>(), search.rank(query, items, limit = -5))
    }

    @Test
    fun emptyItemsRankEmpty() {
        assertEquals(emptyList<Long>(), search.rank(query, emptyList(), limit = 10))
    }

    @Test
    fun usesCosineNotRawDotProduct() {
        // Big-magnitude vector at 45 degrees vs small vector nearly parallel:
        // dot would pick id 1 (10.0 vs 0.9), cosine must pick id 2.
        val items = listOf(
            1L to floatArrayOf(10f, 10f),   // cos 0.7071, dot 10
            2L to floatArrayOf(0.9f, 0.1f), // cos 0.9938, dot 0.9
        )
        assertEquals(listOf(2L, 1L), search.rank(query, items, limit = 10))
    }

    @Test
    fun skipsMismatchedDimensionsAndZeroVectors() {
        val items = listOf(
            1L to floatArrayOf(0.5f, 0.5f, 0.5f), // wrong dimension: skipped
            2L to floatArrayOf(1f, 0f),
            3L to FloatArray(0),                  // empty: skipped
            4L to floatArrayOf(0f, 0f),           // zero vector: ranks last, never crashes
        )
        assertEquals(listOf(2L, 4L), search.rank(query, items, limit = 10))
    }

    @Test
    fun tiesKeepInsertionOrder() {
        val items = listOf(
            7L to floatArrayOf(1f, 0f),
            8L to floatArrayOf(2f, 0f), // same direction, same cosine
        )
        assertEquals(listOf(7L, 8L), search.rank(query, items, limit = 10))
    }

    @Test
    fun blankQueryOrNonPositiveLimitSearchesEmpty() = runBlocking {
        // Early-outs that must not touch the encoder or the database.
        assertTrue(search.search(context, "").isEmpty())
        assertTrue(search.search(context, "   \n").isEmpty())
        assertTrue(search.search(context, "crying laughing", limit = 0).isEmpty())
    }
}
