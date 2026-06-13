package com.candidstickers.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Embeddings (CLIP 512-d, MobileFaceNet 192-d, person centroids) are stored in
 * SQLite BLOBs as little-endian float32 — the layout sqlite-vec expects, so a
 * later swap to vector queries needs no data migration.
 */
object FloatBlob {

    fun toBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(floats)
        return buffer.array()
    }

    fun toFloats(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "Blob size ${bytes.size} is not a multiple of 4" }
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }
}
