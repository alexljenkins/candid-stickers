package com.candidstickers.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.candidstickers.data.CandidCrop
import com.candidstickers.data.CropDb
import com.candidstickers.scan.ScanPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MinerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = CropDb.getInstance(app)
    private val pipeline = ScanPipeline(app, db)
    private var scanJob: Job? = null

    var crops by mutableStateOf<List<CandidCrop>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var progress by mutableStateOf<ScanPipeline.Progress?>(null)
        private set

    fun loadExisting() {
        viewModelScope.launch {
            crops = withContext(Dispatchers.IO) { db.topCrops() }
        }
    }

    fun startScan() {
        if (scanning) return
        scanning = true
        progress = null
        scanJob = viewModelScope.launch {
            try {
                pipeline.scan(
                    onProgress = { p -> withContext(Dispatchers.Main) { progress = p } },
                    onCrop = { crop ->
                        withContext(Dispatchers.Main) {
                            crops = (crops + crop).sortedByDescending { it.score }
                        }
                    },
                )
            } finally {
                withContext(Dispatchers.Main) { scanning = false }
            }
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        // CropDb is a process-wide singleton (shared with StickerContentProvider) — never close it.
    }
}
