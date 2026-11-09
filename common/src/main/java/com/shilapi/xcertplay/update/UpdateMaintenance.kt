package com.shilapi.xcertplay.update

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs update-file maintenance once per process after startup activity has settled. */
internal object UpdateMaintenance {
    private const val DELAY_MILLIS = 60_000L
    private val scheduled = AtomicBoolean()

    fun schedule(context: Context, currentVersionCode: Long) {
        if (!scheduled.compareAndSet(false, true)) return
        val app = context.applicationContext
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-update-maintenance").apply { isDaemon = true }
        }
        try {
            executor.schedule({
                try {
                    UpdateDownloadCache.cleanupForProcessStart(app)
                    UpdateApkExport.pruneHistory(app, currentVersionCode)
                } finally {
                    executor.shutdown()
                }
            }, DELAY_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: RuntimeException) {
            executor.shutdownNow()
            scheduled.set(false)
            throw failure
        }
    }
}
