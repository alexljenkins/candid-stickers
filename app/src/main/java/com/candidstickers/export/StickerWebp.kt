package com.candidstickers.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** WhatsApp sticker constraints (WhatsApp/stickers Android README). */
internal const val STICKER_SIZE = 512
internal const val STICKER_MAX_BYTES = 100 * 1024
internal const val TRAY_SIZE = 96
internal const val TRAY_MAX_BYTES = 50 * 1024

private val WEBP_QUALITY_LADDER = intArrayOf(90, 80, 70, 60, 50)

/**
 * Draws [src] fit-center onto an exactly 512x512 transparent canvas and encodes
 * WebP, stepping down quality until the file is <= 100 KiB. False when even the
 * lowest rung is too big or the platform refuses to encode.
 */
fun renderStickerWebp(src: Bitmap, dest: File): Boolean {
    val canvas = fitCenter(src, STICKER_SIZE)
    try {
        for (quality in WEBP_QUALITY_LADDER) {
            val out = ByteArrayOutputStream()
            if (!compressWebp(canvas, quality, out)) return false
            if (out.size() <= STICKER_MAX_BYTES) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(out.toByteArray())
                return true
            }
        }
        return false
    } finally {
        canvas.recycle()
    }
}

/** 96x96 fit-center PNG tray icon; halves source detail in the (unlikely) event it tops 50 KiB. */
fun renderTrayPng(src: Bitmap, dest: File): Boolean {
    var working = src
    var ownsWorking = false
    try {
        repeat(4) {
            val canvas = fitCenter(working, TRAY_SIZE)
            val out = ByteArrayOutputStream()
            val ok = canvas.compress(Bitmap.CompressFormat.PNG, 100, out)
            canvas.recycle()
            if (ok && out.size() <= TRAY_MAX_BYTES) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(out.toByteArray())
                return true
            }
            val halved = Bitmap.createScaledBitmap(
                working, max(1, working.width / 2), max(1, working.height / 2), true
            )
            if (ownsWorking) working.recycle()
            working = halved
            ownsWorking = true
        }
        return false
    } finally {
        if (ownsWorking) working.recycle()
    }
}

/** [src] scaled fit-center onto a [size]x[size] transparent ARGB_8888 canvas. */
internal fun fitCenter(src: Bitmap, size: Int): Bitmap {
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
    val w = src.width * scale
    val h = src.height * scale
    val left = (size - w) / 2f
    val top = (size - h) / 2f
    Canvas(out).drawBitmap(src, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

internal fun compressWebp(bitmap: Bitmap, quality: Int, out: OutputStream): Boolean =
    if (Build.VERSION.SDK_INT >= 30) {
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
    } else {
        // Legacy WEBP is lossy for quality < 100; the ladder never reaches 100.
        @Suppress("DEPRECATION")
        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
    }
