package com.example.ptt.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.ptt.setup.CheckInResult
import com.example.ptt.setup.DeviceRegistration
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Answers a `check_in` push: re-evaluates the checklist and sends a full `PUT /v1/devices/me`. */
@HiltWorker
class CheckInWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val registration: DeviceRegistration
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (registration.checkIn()) {
        CheckInResult.Sent -> Result.success()
        CheckInResult.Retry -> Result.retry()
        CheckInResult.Failed -> Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "ptt-check-in"
    }

    class WorkManagerScheduler(private val context: Context) : CheckInScheduler {
        override fun schedule() {
            val request = OneTimeWorkRequestBuilder<CheckInWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: a pending or retrying job re-reads the checklist when it runs, so a second push adds nothing.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
