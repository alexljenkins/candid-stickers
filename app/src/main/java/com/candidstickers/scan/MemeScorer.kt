package com.candidstickers.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns Face Landmarker blendshape coefficients into a "meme-ability" score.
 * Pure thresholds/weights on the 52 ARKit-style coefficients — no ML of its own.
 */
object MemeScorer {

    data class Result(val score: Float, val reason: String)

    /** Crops scoring below this are not worth keeping. */
    const val KEEP_THRESHOLD = 0.32f

    fun score(b: Map<String, Float>): Result {
        fun v(name: String) = b[name] ?: 0f

        val eyesShut = min(v("eyeBlinkLeft"), v("eyeBlinkRight"))
        val jawOpen = v("jawOpen")
        val wink = abs(v("eyeBlinkLeft") - v("eyeBlinkRight"))

        val signals = listOf(
            "eyes shut" to eyesShut * 1.0f,
            "jaw drop" to jawOpen * 0.95f,
            "shocked" to min(v("eyeWideLeft"), v("eyeWideRight")) * 0.9f,
            "cheek puff" to v("cheekPuff") * 1.0f,
            "duck face" to v("mouthPucker") * 0.85f,
            "brow raise" to v("browInnerUp") * 0.7f,
            "scowl" to max(v("browDownLeft"), v("browDownRight")) * 0.7f,
            "sneer" to max(v("noseSneerLeft"), v("noseSneerRight")) * 0.8f,
            "grimace" to max(v("mouthStretchLeft"), v("mouthStretchRight")) * 0.8f,
            "gasp" to v("mouthFunnel") * 0.8f,
            "wink" to wink * 0.65f,
            "squint" to min(v("eyeSquintLeft"), v("eyeSquintRight")) * 0.6f,
        ).sortedByDescending { it.second }

        val (bestName, best) = signals[0]
        val second = signals[1].second

        // Combos beat single signals: eyes shut + mouth open is peak candid.
        var combo = 0f
        var reason = bestName
        if (min(eyesShut, jawOpen) > 0.4f) {
            combo = 0.25f
            reason = "mid-sneeze"
        }

        val score = (best + 0.35f * second + combo).coerceIn(0f, 1f)
        return Result(score, reason)
    }
}
