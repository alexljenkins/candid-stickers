package com.candidstickers.scan

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * MobileFaceNet via the LiteRT [Interpreter] (org.tensorflow.lite API).
 * Input: 112x112 RGB float32 NHWC, normalized (x − 127.5) / 127.5.
 * Output: 192-d embedding; the model emits unit-norm vectors, and [embed]
 * re-normalizes as cheap insurance so downstream cosine == dot exactly.
 *
 * [Interpreter] is not thread-safe; [embed] serializes callers on an internal
 * lock and reuses one direct input buffer.
 */
class FaceEmbedder(context: Context) : AutoCloseable {

    private val lock = Any()

    private val interpreter = Interpreter(
        loadModel(context),
        Interpreter.Options().setNumThreads(NUM_THREADS),
    )

    private val input: ByteBuffer = ByteBuffer
        .allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNELS * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    /** @param aligned a 112x112 crop from [FaceAligner.align]. */
    fun embed(aligned: Bitmap): FloatArray {
        require(aligned.width == INPUT_SIZE && aligned.height == INPUT_SIZE) {
            "Expected ${INPUT_SIZE}x$INPUT_SIZE aligned face, got ${aligned.width}x${aligned.height}"
        }
        synchronized(lock) {
            aligned.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            input.rewind()
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16 and 0xFF) - 127.5f) / 127.5f) // R
                input.putFloat(((pixel shr 8 and 0xFF) - 127.5f) / 127.5f)  // G
                input.putFloat(((pixel and 0xFF) - 127.5f) / 127.5f)        // B
            }
            input.rewind()

            val output = Array(1) { FloatArray(EMBEDDING_DIM) }
            interpreter.run(input, output)
            return l2Normalized(output[0])
        }
    }

    override fun close() {
        synchronized(lock) { interpreter.close() }
    }

    private fun l2Normalized(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        if (norm <= 0f || !norm.isFinite()) return v
        for (i in v.indices) v[i] /= norm
        return v
    }

    private companion object {
        const val MODEL_ASSET = "mobilefacenet.tflite"
        const val INPUT_SIZE = 112
        const val CHANNELS = 3
        const val BYTES_PER_FLOAT = 4
        const val EMBEDDING_DIM = 192
        const val NUM_THREADS = 2

        /** Memory-maps the asset (noCompress keeps .tflite stored uncompressed). */
        fun loadModel(context: Context): MappedByteBuffer =
            context.assets.openFd(MODEL_ASSET).use { afd ->
                FileInputStream(afd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
    }
}
