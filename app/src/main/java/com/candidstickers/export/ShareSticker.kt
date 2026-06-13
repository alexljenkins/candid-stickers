package com.candidstickers.export

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.candidstickers.data.CandidCrop
import java.io.File

/** Shares a single crop PNG through the app's FileProvider via an ACTION_SEND chooser. */
object ShareSticker {

    private const val AUTHORITY = "com.candidstickers.fileprovider"

    fun share(context: Context, crop: CandidCrop) {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, File(crop.cropPath))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("sticker", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share sticker")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
