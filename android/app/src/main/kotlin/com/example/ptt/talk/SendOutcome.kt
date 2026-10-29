package com.example.ptt.talk

import androidx.annotation.StringRes
import com.example.ptt.R

/** What a send attempt means for the screen that made it; shared by the Family screen and the send entry point. */
sealed interface SendOutcome {
    /** The page was stored and queued: open the sender's screen for it. */
    data class Open(val pageId: String) : SendOutcome

    /** A live page holds the slot (with [peerName], if known): ask before ending it. */
    data class Ask(val peerName: String?) : SendOutcome

    /** Nothing was sent; [name] fills the message's `%1$s`, if it has one. */
    data class Message(@StringRes val res: Int, val name: String? = null) : SendOutcome
}

/** Sends a [level] page to [userId] (called [name]) and turns the [SendResult] into a [SendOutcome]. */
internal suspend fun PageSender.attempt(
    userId: String,
    name: String?,
    level: String,
    endCurrent: Boolean
): SendOutcome = when (val result = send(userId, level, endCurrent)) {
    is SendResult.Sent -> SendOutcome.Open(result.pageId)
    is SendResult.Busy -> SendOutcome.Ask(result.peerName)
    SendResult.NoDevices -> SendOutcome.Message(R.string.family_no_phones, name)
    else -> SendOutcome.Message(result.failureMessage())
}
