package com.example.ptt.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** The daily update check (docs/protocol.md section 14.4). */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: UpdateChecker
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (checker.check()) {
        // Worth another try soon (WorkManager backs off); after a few the next daily run takes over.
        is UpdateOutcome.Failed, UpdateOutcome.Deferred ->
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()

        else -> Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "ptt-update-check"
        private const val MAX_RETRIES = 4

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
