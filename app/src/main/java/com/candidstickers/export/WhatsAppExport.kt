package com.candidstickers.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.candidstickers.data.PackRow

/** Builds and interprets WhatsApp's add-sticker-pack intent flow. */
object WhatsAppExport {

    const val CONSUMER_PACKAGE = "com.whatsapp"
    const val BUSINESS_PACKAGE = "com.whatsapp.w4b"

    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
    private const val EXTRA_VALIDATION_ERROR = "validation_error"

    /** True when WhatsApp consumer or business is visible (manifest `<queries>` covers both). */
    fun isInstalled(context: Context): Boolean =
        isVisible(context, CONSUMER_PACKAGE) || isVisible(context, BUSINESS_PACKAGE)

    /**
     * Chooser-wrapped ENABLE_STICKER_PACK intent. Launch via an ActivityResult
     * launcher inside a try/catch for [android.content.ActivityNotFoundException]
     * (WhatsApp can vanish between [isInstalled] and launch), then feed the
     * result to [parseResult].
     */
    fun addPackIntent(pack: PackRow): Intent {
        val intent = Intent(ACTION_ENABLE_STICKER_PACK)
            .putExtra(EXTRA_STICKER_PACK_ID, pack.identifier)
            .putExtra(EXTRA_STICKER_PACK_AUTHORITY, StickerContentProvider.AUTHORITY)
            .putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
        return Intent.createChooser(intent, "Add to WhatsApp")
    }

    sealed class AddResult {
        object Added : AddResult()
        object Declined : AddResult()
        data class ValidationError(val message: String) : AddResult()
    }

    fun parseResult(resultCode: Int, data: Intent?): AddResult = when {
        resultCode == Activity.RESULT_OK -> AddResult.Added
        data != null ->
            data.getStringExtra(EXTRA_VALIDATION_ERROR)?.let { AddResult.ValidationError(it) }
                ?: AddResult.Declined
        else -> AddResult.Declined
    }

    private fun isVisible(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
