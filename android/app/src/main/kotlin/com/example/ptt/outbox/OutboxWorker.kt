package com.example.ptt.outbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * The retry path that survives process death: sends whatever the outbox still holds. Returns
 * `retry()` while rows remain (WorkManager's own exponential backoff then applies on top of each
 * row's schedule), and success when the outbox is empty or sending is blocked on enrollment (the
 * enrollment flow kicks the outbox again).
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outbox: Outbox
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val result = outbox.drain()
        if (result.nextAttemptAt != null && !result.blocked) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "outbox pass failed: ${e.javaClass.simpleName}")
        Result.retry()
    }

    /** Only used for expedited work on Android 10 and 11, which runs it as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.outbox_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.outbox_notification_title))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "OutboxWorker"
        const val UNIQUE_NAME = "ptt-outbox"
        private const val CHANNEL_ID = "outbox"
        private const val NOTIFICATION_ID = 0x0B0
        private const val BACKOFF_SECONDS = 10L
    }

    /**
     * Enqueues the expedited, network-constrained job, appended after a running one so a row inserted
     * while that one finishes is not stranded.
     */
    class WorkManagerScheduler(private val context: Context) : OutboxScheduler {
        override fun schedule() {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(
                context
            ).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
