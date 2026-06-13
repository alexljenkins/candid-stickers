package com.candidstickers.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Round-trips for the v3 CLIP/tag-bank/persons API. */
@RunWith(AndroidJUnit4::class)
class CropDbClipFaceTest {

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

    private fun newCrop(mediaId: Long, faceIndex: Int = 0, score: Float = 0.5f): Long {
        db.markScanned(mediaId, "content://media/external/images/media/$mediaId", 0L, 1)
        return db.insertCrop(mediaId, faceIndex, score, "test", "/crops/$mediaId-$faceIndex.png")
    }

    @Test
    fun clipRoundTrip() {
        val a = newCrop(1, score = 0.9f)
        val b = newCrop(2, score = 0.8f)

        assertEquals(listOf(a, b), db.cropsMissingClip().map { it.id })
        assertEquals(listOf(a), db.cropsMissingClip(limit = 1).map { it.id })
        assertTrue(db.cropEmbeddings().isEmpty())

        val blob = FloatBlob.toBytes(floatArrayOf(0.25f, -1f, 3.5f))
        db.updateCropClip(a, blob, """["crying laughing","side eye"]""")

        assertEquals(listOf(b), db.cropsMissingClip().map { it.id })
        val embeddings = db.cropEmbeddings()
        assertEquals(1, embeddings.size)
        assertEquals(a, embeddings[0].first)
        assertArrayEquals(blob, embeddings[0].second)

        val crop = db.topCrops().first { it.id == a }
        assertEquals(listOf("crying laughing", "side eye"), crop.tags)
        assertNull(crop.personId)
        // cropsByIds carries the same v3 fields
        assertEquals(crop, db.cropsByIds(listOf(a)).single())
    }

    @Test
    fun malformedTagsJsonReadsAsEmpty() {
        val a = newCrop(1)
        db.updateCropClip(a, FloatBlob.toBytes(floatArrayOf(1f)), "not json")
        assertEquals(emptyList<String>(), db.topCrops().single().tags)
    }

    @Test
    fun tagBankRoundTripAndReplace() {
        assertTrue(db.tagBank().isEmpty())
        db.putTagBank("crying laughing", byteArrayOf(1, 2))
        db.putTagBank("side eye", byteArrayOf(3, 4))
        db.putTagBank("crying laughing", byteArrayOf(5, 6)) // upsert wins

        val bank = db.tagBank()
        assertEquals(setOf("crying laughing", "side eye"), bank.keys)
        assertArrayEquals(byteArrayOf(5, 6), bank["crying laughing"])
        assertArrayEquals(byteArrayOf(3, 4), bank["side eye"])
    }

    @Test
    fun faceAndPersonsRoundTrip() {
        val a = newCrop(1, score = 0.9f)
        val b = newCrop(2, score = 0.7f)
        val c = newCrop(3, score = 0.8f)

        assertEquals(listOf(a, c, b), db.cropsMissingFace().map { it.id })

        val centroid1 = FloatBlob.toBytes(floatArrayOf(1f, 0f))
        val person1 = db.insertPerson(centroid1)
        assertTrue(person1 > 0)
        db.updateCropFace(a, person1, FloatBlob.toBytes(floatArrayOf(0.9f, 0.1f)))

        assertEquals(listOf(c, b), db.cropsMissingFace().map { it.id })
        assertEquals(person1, db.topCrops().first { it.id == a }.personId)

        // centroids carry face_count
        var centroids = db.personCentroids()
        assertEquals(1, centroids.size)
        assertEquals(person1, centroids[0].id)
        assertEquals(1, centroids[0].faceCount)
        assertArrayEquals(centroid1, centroids[0].centroid)

        val centroid1b = FloatBlob.toBytes(floatArrayOf(0.95f, 0.05f))
        db.updatePersonCentroid(person1, centroid1b, 2)
        centroids = db.personCentroids()
        assertEquals(2, centroids[0].faceCount)
        assertArrayEquals(centroid1b, centroids[0].centroid)

        db.renamePerson(person1, "Alex")
        var persons = db.persons()
        assertEquals(1, persons.size)
        assertEquals(PersonRow(person1, "Alex", 2, "/crops/1-0.png"), persons[0])

        // second person; cover = that person's highest-score crop
        val person2 = db.insertPerson(FloatBlob.toBytes(floatArrayOf(0f, 1f)))
        db.updateCropFace(b, person2, FloatBlob.toBytes(floatArrayOf(0.1f, 0.9f)))
        db.updateCropFace(c, person2, FloatBlob.toBytes(floatArrayOf(0.2f, 0.8f)))
        db.updatePersonCentroid(person2, FloatBlob.toBytes(floatArrayOf(0f, 1f)), 2)
        persons = db.persons()
        assertEquals(2, persons.size)
        assertEquals("/crops/3-0.png", persons.first { it.id == person2 }.coverCropPath)

        assertEquals(listOf(c, b), db.cropsForPerson(person2).map { it.id })

        // merge: crops reassigned, counts summed, loser deleted
        db.mergePersons(person1, person2)
        persons = db.persons()
        assertEquals(1, persons.size)
        assertEquals(person1, persons[0].id)
        assertEquals(4, persons[0].faceCount)
        assertEquals(listOf(a, c, b), db.cropsForPerson(person1).map { it.id })
        assertTrue(db.cropsForPerson(person2).isEmpty())
        assertEquals(listOf(person1), db.personCentroids().map { it.id })
    }
}
