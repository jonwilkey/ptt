package com.example.ptt.push

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.ptt.R
import com.example.ptt.alert.AlertChannels
import com.example.ptt.setup.RosterSync
import com.example.ptt.setup.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Runs [RosterSync.sync] for a `roster` push, as expedited work so it outlives the short-lived FCM service. */
@HiltWorker
class RosterSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val rosterSync: RosterSync
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (rosterSync.sync()) {
        SyncResult.Unavailable -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        else -> Result.success()
    }

    /** Only used for expedited work on Android 10 and 11, which runs it as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        AlertChannels.create(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, AlertChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.notif_roster_sync))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE_NAME = "ptt-roster-sync"
        private const val NOTIFICATION_ID = 0x0B1
        private const val BACKOFF_SECONDS = 10L
        private const val MAX_ATTEMPTS = 5
    }

    class WorkManagerScheduler(private val context: Context) : RosterSyncScheduler {
        override fun schedule() {
            val request = OneTimeWorkRequestBuilder<RosterSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // Appended, so a roster push that arrives during a sync still gets its own pass.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
