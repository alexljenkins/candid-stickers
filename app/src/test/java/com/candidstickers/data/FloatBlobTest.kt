package com.candidstickers.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FloatBlobTest {

    @Test
    fun roundTrip() {
        val floats = floatArrayOf(0f, 1f, -1f, 0.5f, Float.MAX_VALUE, Float.MIN_VALUE, 3.14159f)
        assertArrayEquals(floats, FloatBlob.toFloats(FloatBlob.toBytes(floats)), 0f)
    }

    @Test
    fun emptyRoundTrip() {
        assertEquals(0, FloatBlob.toBytes(FloatArray(0)).size)
        assertEquals(0, FloatBlob.toFloats(ByteArray(0)).size)
    }

    @Test
    fun encodingIsLittleEndianFloat32() {
        // 1.0f = 0x3F800000 big-endian, so LE bytes are 00 00 80 3F.
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F),
            FloatBlob.toBytes(floatArrayOf(1f))
        )
    }

    @Test
    fun rejectsBlobNotMultipleOfFour() {
        assertThrows(IllegalArgumentException::class.java) {
            FloatBlob.toFloats(ByteArray(5))
        }
    }
}
