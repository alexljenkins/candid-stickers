package com.candidstickers.export

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.candidstickers.data.CropDb
import com.candidstickers.data.PackRow
import java.io.File

/**
 * WhatsApp reads sticker packs through this provider; URI shapes and column
 * names are WhatsApp's contract — do not rename (see the manifest for the
 * authority + com.whatsapp.sticker.READ permission). Runs standalone: WhatsApp
 * queries it without MainActivity ever having launched.
 */
class StickerContentProvider : ContentProvider() {

    private val db by lazy { CropDb.getInstance(context!!) }

    override fun onCreate(): Boolean {
        check(AUTHORITY.startsWith(context!!.packageName)) {
            "Provider authority must start with the package name"
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor = when (MATCHER.match(uri)) {
        CODE_METADATA -> metadataCursor(uri, db.packs())
        CODE_METADATA_SINGLE ->
            // Unknown pack -> empty cursor with the full column set, never null.
            metadataCursor(uri, listOfNotNull(db.packByIdentifier(uri.lastPathSegment.orEmpty())))
        CODE_STICKERS -> stickersCursor(uri)
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        CODE_METADATA -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$METADATA"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$AUTHORITY.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$STICKERS"
        CODE_STICKERS_ASSET -> {
            val name = uri.lastPathSegment.orEmpty()
            when {
                name.endsWith(".png") -> "image/png"
                name.endsWith(".webp") -> "image/webp"
                else -> throw IllegalArgumentException("Unknown asset type: $uri")
            }
        }
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (MATCHER.match(uri) != CODE_STICKERS_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val identifier = segments[1]
        val fileName = segments[2]

        // Serve only files the database knows about — anything else (including
        // traversal attempts) falls through to null.
        val pack = db.packByIdentifier(identifier) ?: return null
        val known = fileName == pack.trayFile ||
            db.packStickers(pack.id).any { it.fileName == fileName }
        if (!known) return null

        val dir = PackManager.packDir(context!!, identifier)
        val file = File(dir, fileName)
        if (file.canonicalFile.parentFile != dir.canonicalFile) return null
        if (!file.isFile) return null
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Not supported")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException("Not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("Not supported")

    private fun metadataCursor(uri: Uri, packs: List<PackRow>): Cursor {
        val cursor = MatrixCursor(METADATA_COLUMNS)
        for (pack in packs) {
            cursor.addRow(
                arrayOf<Any?>(
                    pack.identifier,
                    pack.name,
                    pack.publisher,
                    pack.trayFile,
                    "", // android_play_store_link
                    "", // ios_app_download_link
                    "", // sticker_pack_publisher_email
                    "", // sticker_pack_publisher_website
                    "", // sticker_pack_privacy_policy_website
                    "", // sticker_pack_license_agreement_website
                    pack.imageDataVersion.toString(),
                    0, // whatsapp_will_not_cache_stickers (deprecated)
                    0, // animated_sticker_pack
                )
            )
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    private fun stickersCursor(uri: Uri): Cursor {
        val cursor = MatrixCursor(STICKER_COLUMNS)
        db.packByIdentifier(uri.lastPathSegment.orEmpty())?.let { pack ->
            for (sticker in db.packStickers(pack.id)) {
                cursor.addRow(arrayOf<Any?>(sticker.fileName, sticker.emojis, null))
            }
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    companion object {
        const val AUTHORITY = "com.candidstickers.stickercontentprovider"

        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        private const val CODE_METADATA = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_STICKERS_ASSET = 4

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, METADATA, CODE_METADATA)
            addURI(AUTHORITY, "$METADATA/*", CODE_METADATA_SINGLE)
            addURI(AUTHORITY, "$STICKERS/*", CODE_STICKERS)
            addURI(AUTHORITY, "$STICKERS_ASSET/*/*", CODE_STICKERS_ASSET)
        }

        /** Exact column names WhatsApp expects — do not change. */
        val METADATA_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
        )

        val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text",
        )
    }
}
