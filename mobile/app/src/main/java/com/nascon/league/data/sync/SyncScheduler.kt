package com.nascon.league.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nascon.league.App
import java.util.concurrent.TimeUnit

const val SYNC_WORK_NAME = "nascon_sync_queue"

/** Unique one-time job: runs when CONNECTED, exponential backoff on retry, KEEP so bursts don't stack. */
class SyncScheduler(context: Context) : SyncScheduling {
    private val appContext = context.applicationContext

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (_: Exception) {
            // WorkManager unavailable (e.g. tests); the in-process triggers still work.
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        return when (container.syncRepository.syncNowForWorker()) {
            SyncOutcome.RETRY -> Result.retry()
            else -> Result.success()
        }
    }
}
