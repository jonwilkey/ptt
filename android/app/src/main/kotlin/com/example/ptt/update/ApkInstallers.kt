package com.example.ptt.update

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ptt.MainActivity
import com.example.ptt.R
import com.example.ptt.alert.AlertChannels
import java.io.File
import java.io.IOException

/** Installs through a [PackageInstaller] session; the answer comes to [UpdateInstallReceiver]. */
class SessionApkInstaller(private val context: Context) : ApkInstaller {
    override fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        // A session left waiting for a confirmation nobody gave would hold an older APK.
        installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            commit(installer, id, file)
        } catch (e: IOException) {
            installer.abandonSession(id)
            throw e
        } catch (e: SecurityException) {
            installer.abandonSession(id)
            throw e
        }
    }

    private fun commit(installer: PackageInstaller, id: Int, file: File) {
        installer.openSession(id).use { session ->
            session.openWrite(SESSION_FILE, 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(resultSender(id))
        }
    }

    private fun resultSender(sessionId: Int) = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
        // The system adds the status extras to the intent, so it cannot be immutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    ).intentSender

    private companion object {
        const val SESSION_FILE = "base.apk"
    }
}

/**
 * The installer's answer. When the system needs the user (the first update, or a phone that will not install
 * silently) it hands over the confirmation screen: opened at once if the user asked for the update on screen,
 * otherwise behind a low-importance "Update ready" notification. Not exported; only the system's PendingIntent
 * reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirmation(intent)?.let { promptUser(context, it) }

            else -> {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                if (status != PackageInstaller.STATUS_SUCCESS) Log.w(TAG, "install failed: status $status")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmation(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private fun promptUser(context: Context, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (InstallPrompt.consumeDirect()) {
            try {
                context.startActivity(confirm)
                return
            } catch (_: SecurityException) {
                // Fall through to the notification.
            }
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AlertChannels.UPDATE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_update_ready_title))
            .setContentText(context.getString(R.string.notif_update_ready_text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "UpdateInstall"
        const val NOTIFICATION_ID = 5
    }
}

/** Set by the Update button: the confirmation it is waiting for should open at once, not wait in a notification. */
object InstallPrompt {
    @Volatile
    private var direct = false

    fun expectDirect() {
        direct = true
    }

    fun consumeDirect(): Boolean = direct.also { direct = false }
}
