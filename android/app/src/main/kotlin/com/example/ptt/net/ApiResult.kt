package com.example.ptt.net

import java.io.IOException

/** Why a request was not even sent. */
enum class NotEnrolledReason {
    /** No server URL or device token is stored. */
    NOT_ENROLLED,

    /** The server answered 401 earlier (revoked or reset); the user must enroll again. */
    NEEDS_REENROLLMENT,

    /** The Keystore cannot decrypt the device token. Never replaced by a plaintext fallback. */
    TOKEN_UNAVAILABLE
}

/** The outcome of one REST call, in the three classes the outbox and the UI need (protocol section 6). */
sealed interface ApiResult<out T> {
    /** 2xx. */
    data class Success<T>(val body: T) : ApiResult<T>

    /** The server (or the response) is definitively negative: do not retry. [code] is the protocol error code. */
    data class Final(val status: Int, val code: String?) : ApiResult<Nothing> {
        val isUnauthorized: Boolean get() = status == HTTP_UNAUTHORIZED
    }

    /**
     * Network failure, 5xx, or 429. [retryAfterMs] is the server's `Retry-After` when it sent one;
     * [status] is null for a transport failure.
     */
    data class Retryable(val status: Int?, val retryAfterMs: Long? = null, val cause: IOException? = null) :
        ApiResult<Nothing>

    /** Failed fast without touching the network. */
    data class NotEnrolled(val reason: NotEnrolledReason) : ApiResult<Nothing>
}

internal const val HTTP_UNAUTHORIZED = 401
