package com.candidstickers.clip

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt

/**
 * MobileCLIP-S0 image/text encoders on ONNX Runtime. Owns both OrtSessions;
 * model bytes are stream-copied from `assets/clip/` into `filesDir/models/`
 * on first use and the sessions are created from file paths (mapping an
 * 85 MB byte array would double peak memory).
 *
 * Preprocessing contracts live in docs/MODELS.md — deviation breaks matching
 * silently. Both encoders' raw outputs are NOT unit-norm; [encodeImage] and
 * [encodeText] L2-normalize, so similarity is a plain dot product.
 */
class ClipEncoder private constructor(
    private val env: OrtEnvironment,
    private val visionSession: OrtSession,
    private val textSession: OrtSession,
    private val tokenizer: ClipTokenizer,
) : AutoCloseable {

    /** 512-d L2-normalized image embedding. */
    suspend fun encodeImage(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val pixels = preprocess(bitmap)
        val shape = longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), shape).use { input ->
            visionSession.run(mapOf(VISION_INPUT to input)).use { result ->
                embeddingFrom(result)
            }
        }
    }

    /** 512-d L2-normalized text embedding. */
    suspend fun encodeText(text: String): FloatArray = withContext(Dispatchers.Default) {
        val ids = tokenizer.encode(text)
        val shape = longArrayOf(1, ClipTokenizer.CONTEXT_LENGTH.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { input ->
            textSession.run(mapOf(TEXT_INPUT to input)).use { result ->
                embeddingFrom(result)
            }
        }
    }

    override fun close() {
        visionSession.close()
        textSession.close()
        // OrtEnvironment is a process-wide singleton; never closed here.
    }

    private fun embeddingFrom(result: OrtSession.Result): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val batch = result.get(0).value as Array<FloatArray>
        return VecMath.l2Normalized(batch[0])
    }

    /**
     * Shortest edge -> 256 (bilinear), center-crop 256x256, RGB scaled to 0-1
     * (`x/255`), NCHW. NO mean/std normalization (`do_normalize=false` for
     * MobileCLIP — applying the usual CLIP-ViT mean/std breaks matching).
     */
    private fun preprocess(source: Bitmap): FloatArray {
        require(source.width > 0 && source.height > 0) { "Empty bitmap" }
        // getPixels is unsupported on hardware bitmaps.
        val software =
            if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                source
            }
        try {
            val scale = IMAGE_SIZE.toFloat() / minOf(software.width, software.height)
            val scaledW = (software.width * scale).roundToInt().coerceAtLeast(IMAGE_SIZE)
            val scaledH = (software.height * scale).roundToInt().coerceAtLeast(IMAGE_SIZE)
            val scaled = if (scaledW == software.width && scaledH == software.height) {
                software
            } else {
                // filter=true is the bilinear path.
                Bitmap.createScaledBitmap(software, scaledW, scaledH, true)
            }
            try {
                val left = (scaledW - IMAGE_SIZE) / 2
                val top = (scaledH - IMAGE_SIZE) / 2
                val argb = IntArray(IMAGE_SIZE * IMAGE_SIZE)
                scaled.getPixels(argb, 0, IMAGE_SIZE, left, top, IMAGE_SIZE, IMAGE_SIZE)

                val plane = IMAGE_SIZE * IMAGE_SIZE
                val out = FloatArray(3 * plane)
                for (i in argb.indices) {
                    val p = argb[i]
                    out[i] = ((p ushr 16) and 0xFF) / 255f          // R
                    out[plane + i] = ((p ushr 8) and 0xFF) / 255f   // G
                    out[2 * plane + i] = (p and 0xFF) / 255f        // B
                }
                return out
            } finally {
                if (scaled !== software) scaled.recycle()
            }
        } finally {
            if (software !== source) software.recycle()
        }
    }

    companion object {
        private const val TAG = "ClipEncoder"

        const val IMAGE_SIZE = 256
        const val EMBEDDING_DIM = 512

        private const val ASSET_DIR = "clip"
        private const val VISION_ASSET = "vision_model_fp16.onnx"
        private const val TEXT_ASSET = "text_model_fp16.onnx"
        private const val TOKENIZER_ASSET = "tokenizer.json"
        private const val VISION_INPUT = "pixel_values"
        private const val TEXT_INPUT = "input_ids"

        /**
         * Builds an encoder, or returns null when the ONNX assets are absent
         * (they are gitignored; see scripts/fetch-models.sh) or initialization
         * fails — callers degrade gracefully to a CLIP-less experience.
         */
        suspend fun create(context: Context): ClipEncoder? = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val present = try {
                app.assets.list(ASSET_DIR)?.toSet().orEmpty()
            } catch (e: IOException) {
                emptySet()
            }
            if (VISION_ASSET !in present || TEXT_ASSET !in present || TOKENIZER_ASSET !in present) {
                Log.i(TAG, "CLIP assets missing from assets/$ASSET_DIR; search/tagging disabled")
                return@withContext null
            }
            try {
                val tokenizerJson = app.assets.open("$ASSET_DIR/$TOKENIZER_ASSET")
                    .bufferedReader()
                    .use { it.readText() }
                val tokenizer = ClipTokenizer(tokenizerJson)

                val modelsDir = File(app.filesDir, "models")
                val visionFile =
                    copyAssetIfNeeded(app, "$ASSET_DIR/$VISION_ASSET", File(modelsDir, VISION_ASSET))
                val textFile =
                    copyAssetIfNeeded(app, "$ASSET_DIR/$TEXT_ASSET", File(modelsDir, TEXT_ASSET))

                val env = OrtEnvironment.getEnvironment()
                OrtSession.SessionOptions().use { options ->
                    // Keep multithreading kind to the rest of the app.
                    options.setIntraOpNumThreads(2)
                    val vision = env.createSession(visionFile.absolutePath, options)
                    val text = try {
                        env.createSession(textFile.absolutePath, options)
                    } catch (t: Throwable) {
                        vision.close()
                        throw t
                    }
                    ClipEncoder(env, vision, text, tokenizer)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "CLIP encoder init failed; search/tagging disabled", t)
                null
            }
        }

        /**
         * Stream-copies an asset to [dest] unless a copy with the expected
         * byte count already exists. Copies go through a `.tmp` file so a
         * crash mid-copy never leaves a truncated model behind.
         */
        private fun copyAssetIfNeeded(context: Context, assetPath: String, dest: File): File {
            val expected = assetLength(context, assetPath)
            if (dest.isFile && dest.length() > 0 && (expected == null || dest.length() == expected)) {
                return dest
            }
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            var copied = 0L
            context.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                    }
                    output.flush()
                }
            }
            val onDisk = tmp.length()
            if (copied == 0L || onDisk != copied || (expected != null && copied != expected)) {
                tmp.delete()
                throw IOException(
                    "Copy of asset $assetPath corrupt: streamed $copied B, " +
                        "on disk $onDisk B, expected ${expected ?: "unknown"} B"
                )
            }
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                throw IOException("Cannot replace stale model file $dest")
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("Cannot move $tmp to $dest")
            }
            return dest
        }

        /** Asset byte count via openFd; null when unknowable (compressed asset). */
        private fun assetLength(context: Context, assetPath: String): Long? = try {
            context.assets.openFd(assetPath).use { fd -> fd.length.takeIf { it > 0 } }
        } catch (e: IOException) {
            null
        }
    }
}
