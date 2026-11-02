package com.example.ptt.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only. Sets [DebugImpairment] from integer extras; an absent extra keeps its current value, and
 * `0`/`0` clears it:
 *
 *     adb shell am broadcast -a com.example.ptt.debug.IMPAIR -n com.example.ptt/.net.ImpairmentReceiver \
 *         --ei delay_ms 300 --ei loss_pct 5
 *
 * The broadcast result data reports what is now in force.
 */
class ImpairmentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val current = DebugImpairment.state.value
        val next = Impairment.of(
            intent.getIntExtra(EXTRA_DELAY_MS, current.delayMs),
            intent.getIntExtra(EXTRA_LOSS_PCT, current.lossPct)
        )
        DebugImpairment.set(next)
        if (isOrderedBroadcast) resultData = "delay_ms=${next.delayMs} loss_pct=${next.lossPct}"
    }

    companion object {
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_LOSS_PCT = "loss_pct"
    }
}
