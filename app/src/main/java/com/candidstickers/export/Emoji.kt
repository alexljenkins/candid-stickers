package com.candidstickers.export

/** Maps [com.candidstickers.scan.MemeScorer] reasons to 1-3 WhatsApp sticker emoji. */
object Emoji {

    val DEFAULT = listOf("😂")

    private val byReason = mapOf(
        "eyes shut" to listOf("😌", "😂"),
        "mid-sneeze" to listOf("🤧", "😫", "😂"),
        "jaw drop" to listOf("😱", "🤯"),
        "shocked" to listOf("😳", "😨"),
        "cheek puff" to listOf("😤", "🐡"),
        "duck face" to listOf("😘", "🦆"),
        "brow raise" to listOf("🤨"),
        "scowl" to listOf("😠", "😡"),
        "sneer" to listOf("😏"),
        "grimace" to listOf("😬"),
        "gasp" to listOf("😲", "😯"),
        "wink" to listOf("😉"),
        "squint" to listOf("😆", "🤔"),
    )

    fun forReason(reason: String): List<String> = byReason[reason] ?: DEFAULT

    /** Stable pack identifier; satisfies WhatsApp's `[\w-.,' ]+` / no-".." / <=128 rules. */
    fun packIdentifier(packDbId: Long): String = "candid-$packDbId"
}
