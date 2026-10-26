package com.example.ptt.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ptt.talk.TalkIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The Acknowledged action and the swipe-away re-post of a page notification. Not exported. */
@AndroidEntryPoint
class AlertActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var engine: AlertEngine

    @Inject
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val pageId = intent.getStringExtra(TalkIntents.EXTRA_PAGE_ID) ?: return
        if (action != ACTION_ACKNOWLEDGE && action != ACTION_REPOST) return
        val pending = goAsync()
        scope.launch {
            try {
                if (action == ACTION_ACKNOWLEDGE) engine.acknowledge(pageId) else engine.repostAfterSwipe(pageId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "com.example.ptt.action.ACKNOWLEDGE"

        /** The user swiped a ringing page's notification away: post it again (alerts anew) while it still rings. */
        const val ACTION_REPOST = "com.example.ptt.action.REPOST"
    }
}
