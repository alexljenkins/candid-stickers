package com.candidstickers.clip

import kotlin.math.sqrt

/** Small float-vector helpers shared by the CLIP encode/rank paths. */
internal object VecMath {

    /** Returned for unrankable pairs; below any sane similarity threshold. */
    const val NO_MATCH = -2f

    /**
     * Cosine similarity, defensive about schema drift: mismatched dimensions
     * or a zero vector yield [NO_MATCH] instead of throwing or NaN. The
     * embeddings stored by this app are already L2-normalized, so this is
     * normally just a dot product.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return NO_MATCH
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        val denominator = sqrt(normA * normB)
        if (denominator < 1e-12) return NO_MATCH
        return (dot / denominator).toFloat()
    }

    /** Unit-length copy of [v]; an (effectively) zero vector is returned as-is. */
    fun l2Normalized(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum)
        if (norm < 1e-12) return v.copyOf()
        val inverse = 1.0 / norm
        return FloatArray(v.size) { i -> (v[i] * inverse).toFloat() }
    }
}
