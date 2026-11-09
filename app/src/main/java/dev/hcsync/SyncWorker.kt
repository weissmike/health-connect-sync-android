package dev.hcsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic background sync. Needs the Health Connect background-read permission to be granted. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val cfg = Config(applicationContext)
        val outcome = Syncer(applicationContext, cfg) { }.run()
        // Not configured / no permissions will not fix themselves; transient failures are retried by WorkManager.
        return if (outcome.ok || !cfg.isConfigured) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "hc-sync"
    }
}
