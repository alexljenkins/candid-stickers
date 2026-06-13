package com.candidstickers.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.candidstickers.data.CropDb
import com.candidstickers.data.FloatBlob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic unit vectors against a real (Robolectric) [CropDb]: cosine
 * thresholds, incremental centroid drift, persistence, and [PersonClusterer.compact].
 */
@RunWith(AndroidJUnit4::class)
class PersonClustererTest {

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

    private fun unit(vararg components: Float): FloatArray {
        val norm = sqrt(components.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(components.size) { components[it] / norm }
    }

    /** Unit vector in 2-d at [deg] degrees from the x axis. */
    private fun atDegrees(deg: Float): FloatArray {
        val rad = Math.toRadians(deg.toDouble())
        return floatArrayOf(cos(rad).toFloat(), sin(rad).toFloat())
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun storedCentroid(personId: Long): FloatArray =
        FloatBlob.toFloats(db.personCentroids().single { it.id == personId }.centroid)

    /** A person row with a crop attached, for verifying compact() reassignment. */
    private fun personWithCrop(mediaId: Long, centroid: FloatArray, faceCount: Int): Pair<Long, Long> {
        val personId = db.insertPerson(FloatBlob.toBytes(centroid))
        if (faceCount != 1) db.updatePersonCentroid(personId, FloatBlob.toBytes(centroid), faceCount)
        db.markScanned(mediaId, "content://media/$mediaId", 0L, 1)
        val cropId = db.insertCrop(mediaId, 0, 0.5f, "test", "/crops/$mediaId.png")
        db.updateCropFace(cropId, personId, FloatBlob.toBytes(centroid))
        return personId to cropId
    }

    @Test
    fun firstFaceCreatesPersonWithEmbeddingAsCentroid() {
        val clusterer = PersonClusterer(db)
        val e = unit(1f, 0f, 0f, 0f)
        val id = clusterer.assign(e)
        assertTrue(id > 0)

        val centroids = db.personCentroids()
        assertEquals(listOf(id), centroids.map { it.id })
        assertEquals(1, centroids.single().faceCount)
        assertEquals(1f, dot(e, storedCentroid(id)), 1e-5f)
    }

    @Test
    fun sameDirectionVectorsClusterTogether() {
        val clusterer = PersonClusterer(db)
        val first = clusterer.assign(unit(1f, 0f, 0f, 0f))
        // cos = 0.9 against the centroid — comfortably above the 0.50 floor.
        val second = clusterer.assign(unit(0.9f, 0.436f, 0f, 0f))

        assertEquals(first, second)
        assertEquals(1, db.persons().size)
        assertEquals(2, db.persons().single().faceCount)
    }

    @Test
    fun orthogonalVectorsSplit() {
        val clusterer = PersonClusterer(db)
        val a = clusterer.assign(unit(1f, 0f, 0f, 0f))
        val b = clusterer.assign(unit(0f, 1f, 0f, 0f))

        assertNotEquals(a, b)
        assertEquals(2, db.persons().size)
        assertTrue(db.persons().all { it.faceCount == 1 })
    }

    @Test
    fun centroidDriftsTowardMembers() {
        val clusterer = PersonClusterer(db)
        val e1 = unit(1f, 0f)
        val e2 = unit(0.6f, 0.8f) // cos(e1, e2) = 0.6 >= 0.50 -> same person
        val id = clusterer.assign(e1)
        assertEquals(id, clusterer.assign(e2))

        // c' = normalize(e1 + e2): the centroid moved off e1 toward e2.
        val expected = unit(1.6f, 0.8f)
        val stored = storedCentroid(id)
        assertEquals(1f, dot(expected, stored), 1e-5f)

        // Probe at cos 0.447 to e1 but 0.8 to the drifted centroid: only the
        // drift makes this face join the cluster.
        val probe = unit(0.4472f, 0.8944f)
        assertTrue(dot(probe, e1) < PersonClusterer.ASSIGN_MIN_COS)
        assertTrue(dot(probe, stored) >= PersonClusterer.ASSIGN_MIN_COS)
        assertEquals(id, clusterer.assign(probe))
        assertEquals(3, db.persons().single().faceCount)
    }

    @Test
    fun freshClustererLoadsPersistedCentroids() {
        val id = PersonClusterer(db).assign(unit(1f, 0f, 0f, 0f))
        // New instance: must pick the person up from the DB, not RAM.
        assertEquals(id, PersonClusterer(db).assign(unit(0.95f, 0.1f, 0f, 0f)))
        assertEquals(2, db.persons().single().faceCount)
    }

    @Test
    fun compactMergesNearDuplicateClustersIntoLargerOne() {
        // cos(c1, c2) = cos(15 deg) ~ 0.966 >= 0.55. id2 has more faces -> wins.
        val c1 = atDegrees(0f)
        val c2 = atDegrees(15f)
        val (person1, crop1) = personWithCrop(mediaId = 1, centroid = c1, faceCount = 1)
        val (person2, crop2) = personWithCrop(mediaId = 2, centroid = c2, faceCount = 3)

        PersonClusterer(db).compact()

        val persons = db.persons()
        assertEquals(listOf(person2), persons.map { it.id })
        assertEquals(4, persons.single().faceCount)
        // Crops of the loser follow the winner.
        assertEquals(setOf(crop1, crop2), db.cropsForPerson(person2).map { it.id }.toSet())
        assertTrue(db.cropsForPerson(person1).isEmpty())

        // Winner centroid = normalize(3*c2 + 1*c1), the face-count-weighted mean.
        val expected = unit(3 * c2[0] + c1[0], 3 * c2[1] + c1[1])
        assertEquals(1f, dot(expected, storedCentroid(person2)), 1e-5f)
    }

    @Test
    fun compactLeavesDistinctClustersAlone() {
        val (person1, _) = personWithCrop(mediaId = 1, centroid = floatArrayOf(1f, 0f), faceCount = 2)
        val (person2, _) = personWithCrop(mediaId = 2, centroid = floatArrayOf(0f, 1f), faceCount = 2)

        PersonClusterer(db).compact()

        assertEquals(setOf(person1, person2), db.persons().map { it.id }.toSet())
        assertTrue(db.persons().all { it.faceCount == 2 })
    }

    @Test
    fun compactCascadesThroughMergedCentroids() {
        // 0/40/70 degrees: only (40,70) crosses 0.55 directly (cos 30 ~ 0.866),
        // but their merged centroid at 55 degrees pulls 0 degrees in too
        // (cos 55 ~ 0.574), even though cos(0,70) ~ 0.342 never qualified.
        val (person1, _) = personWithCrop(mediaId = 1, centroid = atDegrees(0f), faceCount = 1)
        val (person2, _) = personWithCrop(mediaId = 2, centroid = atDegrees(40f), faceCount = 1)
        val (person3, _) = personWithCrop(mediaId = 3, centroid = atDegrees(70f), faceCount = 1)

        PersonClusterer(db).compact()

        val persons = db.persons()
        assertEquals(1, persons.size)
        // First merge: equal counts, smaller id (person2) wins; it then outweighs person1.
        assertEquals(person2, persons.single().id)
        assertEquals(3, persons.single().faceCount)
        assertEquals(3, db.cropsForPerson(person2).size)
        assertTrue(db.cropsForPerson(person1).isEmpty())
        assertTrue(db.cropsForPerson(person3).isEmpty())
    }

    @Test
    fun compactOnEmptyOrSingletonDbIsNoOp() {
        PersonClusterer(db).compact()
        assertTrue(db.persons().isEmpty())

        val (person1, _) = personWithCrop(mediaId = 1, centroid = floatArrayOf(1f, 0f), faceCount = 1)
        PersonClusterer(db).compact()
        assertEquals(listOf(person1), db.persons().map { it.id })
    }
}
