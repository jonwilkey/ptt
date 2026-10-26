package com.example.ptt.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.IncomingPage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Debug builds only (it lives in the debug source set, so the release APK has neither the class nor the
 * manifest entry). Turns the broadcast's string extras into an FCM data map and hands it to the same
 * [PushDispatcher] the real service uses, so alert and answer flows can be driven on an emulator without
 * Firebase:
 *
 *     adb shell am broadcast -a com.example.ptt.debug.FAKE_PUSH -n com.example.ptt/.push.FakePushReceiver \
 *         --es type page_cancel --es page_id <uuid> --es reason missed
 *
 * A real `page` needs an envelope signed by a roster device, which cannot be typed on a command line. The
 * extra type `debug_page` (`--es level normal|urgent --es from_name Mom [--es page_id <id>]`) therefore
 * starts the alert directly, skipping the envelope check and `delivered`, to exercise sound, vibration,
 * notifications and the full-screen intent.
 */
@AndroidEntryPoint
class FakePushReceiver : BroadcastReceiver() {
    @Inject
    lateinit var dispatcher: PushDispatcher

    @Inject
    lateinit var engine: AlertEngine

    @Inject
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.extras?.keySet().orEmpty()
            .mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }
            .toMap()
        val pending = goAsync()
        scope.launch {
            try {
                if (data["type"] == "debug_page") startDebugPage(data) else dispatcher.dispatch(data)
            } finally {
                pending.finish()
            }
        }
    }

    private fun startDebugPage(data: Map<String, String>) {
        val page = IncomingPage(
            pageId = data["page_id"] ?: java.util.UUID.randomUUID().toString(),
            level = data["level"] ?: "normal",
            fromUserId = "debug",
            fromName = data["from_name"] ?: "Debug caller"
        )
        engine.start(page)
    }
}
