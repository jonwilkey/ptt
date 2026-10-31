package com.example.ptt.talk

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ptt.alert.SenderWaiting
import com.example.ptt.alert.WaitingNotifier
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.PageRole
import com.example.ptt.setup.RosterSync
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The foreground service (type `microphone`) that hosts the live-talk session of one page. Rules (spec, "Talk and
 * audio"):
 * - started only while [TalkActivity] is visible and RECORD_AUDIO is granted ([start] refuses otherwise, because
 *   Android rejects a microphone foreground service without the permission);
 * - kept running if the sender leaves the screen while waiting; stopped by the controller when the page or
 *   session ends (or the server has no live talk), which also brings the plain "Waiting for ..." notification back;
 * - its ongoing notification is the "Waiting for <name>" notification while the sender waits (the sender's own
 *   copy, posted by [SenderWaiting], stands down for the hosted page) and "Talking with <name>" in a conversation.
 */
@AndroidEntryPoint
class TalkService : Service() {
    @Inject
    lateinit var controller: TalkSessionController

    @Inject
    lateinit var hosting: TalkHosting

    @Inject
    lateinit var activePage: ActivePageRepository

    @Inject
    lateinit var rosterSync: RosterSync

    @Inject
    lateinit var senderWaiting: SenderWaiting

    private var scope: CoroutineScope? = null
    internal var pageId: String? = null
        private set

    @Volatile
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val page = intent?.getStringExtra(TalkIntents.EXTRA_PAGE_ID)
        val fallbackName = intent?.getStringExtra(TalkIntents.EXTRA_CALLER_NAME)
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Always enter the foreground first: a service started with startForegroundService that stops without
        // doing so is a crash. Without the microphone permission Android refuses, and we leave.
        val foreground = enterForeground(WaitingNotifier.build(this, page ?: NO_PAGE, fallbackName, "sending"))
        if (!foreground || page == null || !granted) {
            stopSelf()
            return START_NOT_STICKY
        }
        pageId?.takeIf { it != page }?.let { controller.stop(it) }
        pageId = page
        current = this
        hosting.host(page)
        watch(page, fallbackName)
        controller.open(page)
        return START_NOT_STICKY
    }

    /** False if Android refused (no microphone permission, or a start from the background that is not allowed). */
    private fun enterForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            // Android 10: the type comes from the manifest.
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "foreground refused: ${e.javaClass.simpleName}")
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
        Log.w(TAG, "foreground not allowed: ${e.javaClass.simpleName}")
        false
    }

    /** Keeps the ongoing notification in step with the session stage and the sender row (and FCM refreshes). */
    private fun watch(page: String, fallbackName: String?) {
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            merge(controller.state.map { }, activePage.active.map { }, hosting.refresh.map { })
                .collect { render(page, fallbackName) }
        }
    }

    private suspend fun render(page: String, fallbackName: String?) {
        try {
            if (destroyed || controller.state.value.stage == SessionStage.ENDED) return
            val row = activePage.current()?.takeIf { it.pageId == page }
            val name = nameOf(row) ?: fallbackName
            val talking = controller.state.value.stage == SessionStage.CONVERSATION || row?.role == PageRole.RECEIVER
            val notification = if (talking) {
                val mode = if (row?.role == PageRole.RECEIVER) TalkIntents.MODE_ANSWER else TalkIntents.MODE_OUTGOING
                WaitingNotifier.buildTalking(this, page, name, mode)
            } else {
                WaitingNotifier.build(this, page, name, row?.state ?: "sending")
            }
            // Posted on the main thread, where onDestroy also runs, so a post can never come after the removal.
            withContext(Dispatchers.Main.immediate) {
                if (!destroyed) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "notification not updated: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun nameOf(row: ActivePageEntity?): String? {
        val peer = row?.peerUserId ?: return null
        return rosterSync.heldRoster()?.devices?.firstOrNull { it.userId == peer }?.displayName
    }

    override fun onDestroy() {
        destroyed = true
        scope?.cancel()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        pageId?.let(controller::stop)
        hosting.host(null)
        if (current === this) current = null
        // The waiting notification of a page that is still waiting comes back.
        senderWaiting.refresh()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TalkService"
        const val NOTIFICATION_ID = 5
        private const val NO_PAGE = "none"

        /** The running service, so the controller can ask it to stop. */
        @Volatile
        var current: TalkService? = null
            private set

        /** True when the microphone permission is held, so the service may be started. */
        fun canStart(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Starts the service for [pageId]. Call only while the talk screen is visible. Returns false, and starts
         * nothing, when RECORD_AUDIO is not granted (the caller shows the setup-checklist hint).
         */
        fun start(context: Context, pageId: String, callerName: String?): Boolean {
            if (!canStart(context)) return false
            val intent = Intent(context, TalkService::class.java)
                .putExtra(TalkIntents.EXTRA_PAGE_ID, pageId)
                .apply { if (callerName != null) putExtra(TalkIntents.EXTRA_CALLER_NAME, callerName) }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "talk service could not start: ${e.javaClass.simpleName}")
                false
            }
        }
    }
}

/** Stops the running [TalkService] (it also stops itself if it never started). */
class AndroidTalkServiceControl : TalkServiceControl {
    override fun stop(pageId: String) {
        TalkService.current?.takeIf { it.pageId == pageId }?.stopSelf()
    }
}
