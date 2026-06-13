package com.candidstickers.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.candidstickers.data.CropDb
import com.candidstickers.scan.ScanPipeline
import java.util.concurrent.TimeUnit

/** Overnight batch miner — only runs while charging so we never cook the battery. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Process-wide singleton (shared with StickerContentProvider) — never closed.
        val db = CropDb.getInstance(applicationContext)
        return try {
            ScanPipeline(applicationContext, db).scan()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "candid-scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScanWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
