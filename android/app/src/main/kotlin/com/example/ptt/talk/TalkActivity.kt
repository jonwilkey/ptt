package com.example.ptt.talk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The intent contract of [TalkActivity]. Every notification and full-screen intent that opens the talk
 * screen is built with [intent], so the screen only has to read these extras.
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
 * The talk screen: the page's progress or the incoming page, then the live conversation with its hold-to-talk
 * button ([TalkScreen]). It shows over the lock screen (the manifest and the calls below both ask for it) and holds
 * nothing but this conversation: it has its own task, so Back and End return to the lock screen. What it does for
 * each [TalkIntents] mode is in [TalkViewModel].
 */
@AndroidEntryPoint
class TalkActivity : ComponentActivity() {
    internal val viewModel: TalkViewModel by viewModels { viewModelFactory ?: defaultViewModelProviderFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        open(intent)
        // Live talk's microphone service may only start while this screen is visible (spec, "Talk and audio").
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .map { it.pageId to it.wantsLiveTalk }
                    .distinctUntilChanged()
                    .collect { (pageId, wants) ->
                        if (wants && viewModel.liveTalkEligible()) {
                            val started = TalkService.start(applicationContext, pageId, viewModel.state.value.caller)
                            viewModel.microphoneMissing(!started && !TalkService.canStart(applicationContext))
                        }
                    }
            }
        }
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                val live by viewModel.live.collectAsState()
                TalkScreen(
                    state,
                    viewModel::answerPressed,
                    viewModel::end,
                    ::finishAndRemoveTask,
                    live,
                    { viewModel.pressTalk() },
                    viewModel::releaseTalk
                )
            }
        }
    }

    /** A hold must never outlive the screen being visible and focused (lock, shade, another window, app switch). */
    override fun onStop() {
        viewModel.releaseTalk()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) viewModel.releaseTalk()
        super.onWindowFocusChanged(hasFocus)
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /** Recreation and a repeated intent for the same page are no-ops: the view model remembers the page. */
    private fun open(intent: Intent) {
        val pageId = intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID)
        if (pageId == null) {
            finishAndRemoveTask()
            return
        }
        viewModel.show(
            pageId,
            intent.getStringExtra(TalkIntents.EXTRA_MODE).orEmpty(),
            intent.getStringExtra(TalkIntents.EXTRA_CALLER_NAME)
        )
    }

    companion object {
        /** Test hook: builds the view model in place of Hilt, so a test can give it fake live-talk seams. */
        @VisibleForTesting
        @Volatile
        var viewModelFactory: ViewModelProvider.Factory? = null
    }
}
