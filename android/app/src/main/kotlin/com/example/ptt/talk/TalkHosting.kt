package com.example.ptt.talk

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which page the running [TalkService] hosts, shared by the pieces that must not duplicate its ongoing
 * notification: the sender's "Waiting for ..." notification stands down for the hosted page, and an FCM
 * `page_answered` for it only refreshes the ongoing notification. A leaf object (no dependencies) so that
 * the push dispatcher, the notifier and the controller can all use it without a cycle.
 */
class TalkHosting {
    private val hosted = MutableStateFlow<String?>(null)
    private val refreshes = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The page the service is up for, or null. */
    val hostedPage: StateFlow<String?> = hosted.asStateFlow()

    /** Emits the page id when something asks the service to re-post its notification. */
    val refresh: SharedFlow<String> = refreshes.asSharedFlow()

    fun host(pageId: String?) {
        hosted.value = pageId
    }

    fun isHosting(pageId: String): Boolean = hosted.value == pageId

    fun requestRefresh(pageId: String) {
        refreshes.tryEmit(pageId)
    }
}

/** Stops the foreground [TalkService] when the session is over (a seam so the controller is testable). */
fun interface TalkServiceControl {
    /** Stops the service if it is the host of [pageId] (a service hosting another page is left alone). */
    fun stop(pageId: String)
}
