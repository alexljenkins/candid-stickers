package com.candidstickers.clip

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped lazy holder for the [ClipEncoder]. The two ORT sessions cost
 * real memory, so the whole process shares one encoder; it lives until the
 * process dies (like [com.candidstickers.data.CropDb]).
 */
object Clip {

    @Volatile
    private var encoder: ClipEncoder? = null

    @Volatile
    private var unavailable = false

    private val mutex = Mutex()

    /**
     * Returns the shared encoder, creating it on first call. Once created it
     * is never re-created; once creation fails (assets missing, init error)
     * this returns null fast without retrying.
     */
    suspend fun get(context: Context): ClipEncoder? {
        encoder?.let { return it }
        if (unavailable) return null
        mutex.withLock {
            encoder?.let { return it }
            if (unavailable) return null
            val created = ClipEncoder.create(context.applicationContext)
            if (created != null) encoder = created else unavailable = true
            return created
        }
    }

    /** Whatever [get] produced so far — null before first creation. */
    fun peek(): ClipEncoder? = encoder

    /** Tests only: drop the cached encoder so the next [get] re-attempts creation. */
    @VisibleForTesting
    fun resetForTesting() {
        encoder?.close()
        encoder = null
        unavailable = false
    }
}
