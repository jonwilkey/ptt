package com.example.ptt.talk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ptt.R

/**
 * The intent contract of [TalkActivity]. Every notification and full-screen intent that opens the talk
 * screen is built with [intent], so the real screen (a later PR) only has to read these extras.
 *
 * - [EXTRA_PAGE_ID]: the page this screen is about (always present).
 * - [EXTRA_MODE]: [MODE_ANSWER] (the receiver tapped the notification: answer the page), [MODE_INCOMING]
 *   (the urgent full-screen intent: show the incoming-page screen with its Answer button, and answer only
 *   when that button is pressed) or [MODE_OUTGOING] (this phone sent the page: show its progress).
 * - [EXTRA_CALLER_NAME]: the other person's name, resolved from the verified roster by whoever built the
 *   intent. Display only; it is absent when the roster could not name them.
 *
 * The intent's data (`ptt://page/<id>/<mode>`) only makes intents of different pages and modes distinct for
 * PendingIntent matching; the extras are the contract.
 */
object TalkIntents {
    const val EXTRA_PAGE_ID = "com.example.ptt.extra.PAGE_ID"
    const val EXTRA_MODE = "com.example.ptt.extra.MODE"
    const val EXTRA_CALLER_NAME = "com.example.ptt.extra.CALLER_NAME"
    const val MODE_ANSWER = "answer"
    const val MODE_INCOMING = "incoming"
    const val MODE_OUTGOING = "outgoing"

    /** `ptt://page/<id>/<tag>`: what tells one page's PendingIntents from another's (intent extras do not). */
    fun identity(pageId: String, tag: String): Uri =
        Uri.Builder().scheme("ptt").authority("page").appendPath(pageId).appendPath(tag).build()

    fun pendingIntent(context: Context, pageId: String, mode: String, callerName: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context, pageId, mode, callerName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun intent(context: Context, pageId: String, mode: String, callerName: String?): Intent =
        Intent(context, TalkActivity::class.java)
            .setData(identity(pageId, mode))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_PAGE_ID, pageId)
            .putExtra(EXTRA_MODE, mode)
            .apply { if (callerName != null) putExtra(EXTRA_CALLER_NAME, callerName) }
}

/**
 * Placeholder for the talk screen, so that notification and full-screen intents resolve. It shows over the
 * lock screen (the manifest and the calls below both ask for it, as the real screen will) and only
 * displays the caller and page id; it takes no action yet.
 */
class TalkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val pageId = intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID).orEmpty()
        val caller = intent.getStringExtra(TalkIntents.EXTRA_CALLER_NAME)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(callerLine(caller))
                    Text(getString(R.string.talk_stub_page_id, pageId))
                }
            }
        }
    }

    private fun callerLine(caller: String?): String = when (caller) {
        null -> getString(R.string.talk_stub_unknown_caller)
        else -> getString(R.string.talk_stub_caller, caller)
    }
}
