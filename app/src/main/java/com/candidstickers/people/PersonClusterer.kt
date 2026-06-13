package com.candidstickers.people

import com.candidstickers.data.CropDb
import com.candidstickers.data.FloatBlob
import kotlin.math.sqrt

/**
 * Greedy incremental centroid clustering over MobileFaceNet embeddings.
 * All vectors are kept unit-norm, so cosine similarity is a plain dot product
 * and [assign] is O(persons) per face.
 *
 * Centroids are loaded from [CropDb.personCentroids] once at construction and
 * mutated in RAM + persisted on every change; create a fresh instance per
 * clustering pass rather than holding one across DB writes made elsewhere.
 * Not thread-safe — confine an instance to one worker thread.
 */
class PersonClusterer(private val db: CropDb) {

    private class Cluster(val id: Long, var centroid: FloatArray, var faceCount: Int)

    private val clusters: MutableList<Cluster> =
        db.personCentroids().mapTo(ArrayList()) {
            Cluster(it.id, l2Normalized(FloatBlob.toFloats(it.centroid)), it.faceCount)
        }

    /**
     * Assigns [embedding] (192-d, unit-norm) to the best-matching person when
     * cosine >= [ASSIGN_MIN_COS], folding it into that centroid
     * (`c' = normalize((c·n + e)/(n+1))`); otherwise creates a new person whose
     * centroid is the embedding itself.
     *
     * @return the person id the face now belongs to.
     */
    fun assign(embedding: FloatArray): Long {
        val e = l2Normalized(embedding.copyOf())

        var best: Cluster? = null
        var bestCos = -2f
        for (cluster in clusters) {
            val cos = dot(cluster.centroid, e)
            if (cos > bestCos) {
                bestCos = cos
                best = cluster
            }
        }

        val hit = best
        if (hit != null && bestCos >= ASSIGN_MIN_COS) {
            val n = hit.faceCount
            val updated = FloatArray(e.size) { i -> hit.centroid[i] * n + e[i] }
            l2Normalized(updated) // == normalize((c·n + e)/(n+1)); the 1/(n+1) cancels
            hit.centroid = updated
            hit.faceCount = n + 1
            db.updatePersonCentroid(hit.id, FloatBlob.toBytes(updated), hit.faceCount)
            return hit.id
        }

        val id = db.insertPerson(FloatBlob.toBytes(e))
        clusters.add(Cluster(id, e, 1))
        return id
    }

    /**
     * Repeatedly merges the most-similar centroid pair while its cosine is
     * >= [MERGE_MIN_COS]. The person with the larger face count wins (smaller
     * id on ties); crops are reassigned via [CropDb.mergePersons] and the
     * winner gets the face-count-weighted, re-normalized centroid.
     */
    fun compact() {
        while (true) {
            var bestI = -1
            var bestJ = -1
            var bestCos = MERGE_MIN_COS
            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val cos = dot(clusters[i].centroid, clusters[j].centroid)
                    if (cos >= bestCos) {
                        bestCos = cos
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI < 0) return

            val a = clusters[bestI]
            val b = clusters[bestJ]
            val winner: Cluster
            val loser: Cluster
            if (a.faceCount > b.faceCount || (a.faceCount == b.faceCount && a.id < b.id)) {
                winner = a; loser = b
            } else {
                winner = b; loser = a
            }

            val merged = FloatArray(winner.centroid.size) { i ->
                winner.centroid[i] * winner.faceCount + loser.centroid[i] * loser.faceCount
            }
            l2Normalized(merged)

            db.mergePersons(winner.id, loser.id)
            winner.centroid = merged
            winner.faceCount += loser.faceCount
            // mergePersons leaves the winner's old centroid; recount + recenter.
            db.updatePersonCentroid(winner.id, FloatBlob.toBytes(merged), winner.faceCount)
            clusters.remove(loser)
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dim mismatch: ${a.size} vs ${b.size}" }
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** In-place L2 normalization; returns its argument for chaining. */
    private fun l2Normalized(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        if (norm > 0f && norm.isFinite()) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    companion object {
        /** Same-person assignment floor for MobileFaceNet cosine similarity. */
        const val ASSIGN_MIN_COS = 0.50f

        /** Two centroids at least this similar are the same person — merge. */
        const val MERGE_MIN_COS = 0.55f

        /** Faces below this cosine to their person's centroid are misfiled (future re-cluster pass). */
        const val EJECT_BELOW_COS = 0.35f
    }
}
