package com.example.ptt.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The debug network impairment (docs/protocol.md section 13.9, Testing notes): [delayMs] is added to each direction
 * of the talk WebSocket, [lossPct] percent of received audio frames are dropped.
 */
data class Impairment(val delayMs: Int = 0, val lossPct: Int = 0) {
    val active: Boolean get() = delayMs > 0 || lossPct > 0

    companion object {
        const val MAX_DELAY_MS = 10_000
        const val MAX_LOSS_PCT = 100

        fun of(delayMs: Int, lossPct: Int) =
            Impairment(delayMs.coerceIn(0, MAX_DELAY_MS), lossPct.coerceIn(0, MAX_LOSS_PCT))
    }
}

/** The one setting of this process, in memory: a process restart clears it. Set by [ImpairmentReceiver]. */
object DebugImpairment {
    private val mutable = MutableStateFlow(Impairment())
    val state: StateFlow<Impairment> = mutable

    fun set(value: Impairment) {
        mutable.value = value
    }
}
