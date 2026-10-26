package com.example.ptt.outbox

import com.example.ptt.data.OutboxKind
import com.example.ptt.net.CreatePageRequest

/** An outgoing action the app must get to the server eventually (spec "Reliable sending"). */
sealed class OutboxAction(val kind: OutboxKind, val pageId: String) {
    /** `POST /v1/pages`. Retried with the same page id, which is the server's idempotency key. */
    class Page(val request: CreatePageRequest) : OutboxAction(OutboxKind.PAGE, request.id)

    class Delivered(pageId: String) : OutboxAction(OutboxKind.DELIVERED, pageId)

    class Acknowledge(pageId: String) : OutboxAction(OutboxKind.ACKNOWLEDGE, pageId)

    class Answer(pageId: String) : OutboxAction(OutboxKind.ANSWER, pageId)

    class Cancel(pageId: String) : OutboxAction(OutboxKind.CANCEL, pageId)
}

/** What a row ended in, published on [Outbox.outcomes] when the row is deleted. */
data class OutboxOutcome(val rowId: String, val kind: OutboxKind, val pageId: String, val result: Result) {
    sealed interface Result {
        /** 2xx. [pageState] is the page state the server reported, when the call returns one. */
        data class Sent(val pageState: String?) : Result

        /** A final error (including the benign 410 `page_ended` and 409 `already_answered`). */
        data class Rejected(val status: Int, val code: String?) : Result

        /** Dropped unsent after the send window (page) or the age cap (other kinds); see ADR 0007. */
        data object Expired : Result

        /** The sender cancelled a page before any attempt was made, so it was never created. */
        data object CancelledLocally : Result
    }
}

/** The result of [Outbox.deliver], i.e. what the receiver does with a pushed page (protocol section 9). */
sealed interface DeliveredOutcome {
    /** Alert as usual. */
    data object Ringing : DeliveredOutcome

    /** Another device already acknowledged: post the notification silently; the page is still answerable. */
    data object Acknowledged : DeliveredOutcome

    /** This device answered it: show the answered page, do not ring. */
    data object Answered : DeliveredOutcome

    /**
     * Drop the page silently: it ended or another device answered (410), it is not ours (403, 404), or
     * this device is no longer authorised (401).
     */
    data class Drop(val status: Int?, val code: String?) : DeliveredOutcome

    /**
     * The server could not be reached (network, 5xx, 429, or the device token is momentarily
     * unreadable). Alert anyway; a `delivered` retry has been queued in the outbox.
     */
    data object Unreachable : DeliveredOutcome
}
