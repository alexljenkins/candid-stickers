package com.candidstickers.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.candidstickers.data.CropDb
import com.candidstickers.data.PackRow
import com.candidstickers.data.PackStickerRow
import com.candidstickers.export.PackManager
import com.candidstickers.export.WhatsAppExport
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PacksViewModel(app: Application) : AndroidViewModel(app) {

    private val db = CropDb.getInstance(app)
    private val packManager = PackManager(app)

    var packs by mutableStateOf<List<PackRow>>(emptyList())
        private set

    /** Stickers per pack id, loaded together with [packs] (a pack holds at most 30). */
    var stickers by mutableStateOf<Map<Long, List<PackStickerRow>>>(emptyMap())
        private set

    var expandedPackId by mutableStateOf<Long?>(null)
        private set

    /** One-shot snackbar text; the UI shows it, then calls [consumeMessage]. */
    var message by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        viewModelScope.launch { loadAll() }
    }

    fun toggleExpanded(packId: Long) {
        expandedPackId = if (expandedPackId == packId) null else packId
    }

    fun createPack(name: String, cropIds: List<Long>, onSuccess: (PackRow) -> Unit = {}) {
        viewModelScope.launch {
            packManager.createPack(name, cropIds)
                .onSuccess { pack ->
                    loadAll()
                    expandedPackId = pack.id
                    onSuccess(pack)
                }
                .onFailure { message = it.message ?: "Couldn't create pack" }
        }
    }

    fun removeSticker(packId: Long, cropId: Long) {
        viewModelScope.launch {
            packManager.removeSticker(packId, cropId)
                .onSuccess {
                    loadAll()
                    message = "Sticker removed"
                }
                .onFailure { message = it.message ?: "Couldn't remove sticker" }
        }
    }

    fun deletePack(packId: Long) {
        viewModelScope.launch {
            try {
                packManager.deletePack(packId)
                if (expandedPackId == packId) expandedPackId = null
                message = "Pack deleted"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Couldn't delete pack"
            }
            loadAll()
        }
    }

    fun onWhatsAppResult(resultCode: Int, data: Intent?) {
        message = when (val result = WhatsAppExport.parseResult(resultCode, data)) {
            is WhatsAppExport.AddResult.Added -> "Added!"
            is WhatsAppExport.AddResult.Declined -> "Declined"
            is WhatsAppExport.AddResult.ValidationError -> result.message
        }
    }

    fun showMessage(text: String) {
        message = text
    }

    fun consumeMessage() {
        message = null
    }

    /** Absolute tray icon file for Coil. */
    fun trayFile(pack: PackRow): File = File(packManager.packDir(pack.identifier), pack.trayFile)

    private suspend fun loadAll() {
        val (rows, byPack) = withContext(Dispatchers.IO) {
            val rows = db.packs()
            rows to rows.associate { it.id to db.packStickers(it.id) }
        }
        packs = rows
        stickers = byPack
    }
}
