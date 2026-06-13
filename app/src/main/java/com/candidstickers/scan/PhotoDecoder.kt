package com.candidstickers.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/**
 * Camera-roll photo decoding shared by [ScanPipeline] (initial scan) and
 * [Enricher] (face backfill re-decodes the source photo). Deterministic for a
 * given image, so face keypoints found during backfill land in the same pixel
 * space the original scan used.
 */
internal object PhotoDecoder {

    /** Longest side of a decoded photo; large enough for face work, small enough to keep peak memory sane. */
    const val DECODE_MAX = 2048

    /** Decodes an orientation-corrected ARGB_8888 software bitmap, longest side <= [DECODE_MAX]. */
    fun decode(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(resolver, uri)
                ) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val maxDim = max(info.size.width, info.size.height)
                    if (maxDim > DECODE_MAX) decoder.setTargetSampleSize(maxDim / DECODE_MAX)
                }.let { bmp ->
                    if (bmp.config != Bitmap.Config.ARGB_8888) {
                        bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                    } else bmp
                }
            } catch (e: Exception) {
                null
            }
        } else {
            decodeLegacy(context, uri)
        }
    }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_MAX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { bmp.recycle() }
    }
}
