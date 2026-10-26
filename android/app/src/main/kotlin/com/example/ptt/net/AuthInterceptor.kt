package com.example.ptt.net

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** Thrown inside a call (OkHttp only lets interceptors fail with [IOException]); mapped to [ApiResult.NotEnrolled]. */
class NotEnrolledException(val reason: NotEnrolledReason, cause: Throwable? = null) :
    IOException(reason.name, cause)

/**
 * Adds `Authorization: Bearer <token>` to every request except `POST /v1/enroll`. A 401 to an
 * authenticated request marks the device as needing re-enrollment (revoked or reset), but only if the
 * token that request carried is still the stored one: a late 401 for an old identity must not flag a
 * fresh enrollment. There are no retries and nothing is wiped here.
 */
class AuthInterceptor(private val tokens: DeviceTokenStore, private val state: EnrollmentState) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath == ENROLL_PATH) return chain.proceed(request)
        val token = try {
            tokens.token()
        } catch (e: DeviceTokenUnavailable) {
            throw NotEnrolledException(NotEnrolledReason.TOKEN_UNAVAILABLE, e)
        } ?: throw NotEnrolledException(NotEnrolledReason.NOT_ENROLLED)
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        if (response.code == HTTP_UNAUTHORIZED && sentTokenIsStillCurrent(token)) state.markNeedsReenrollment()
        return response
    }

    /** False when the token was replaced (or cleared, or cannot be read) while the request was in flight. */
    private fun sentTokenIsStillCurrent(sent: String): Boolean = try {
        tokens.token() == sent
    } catch (_: DeviceTokenUnavailable) {
        false
    }

    companion object {
        const val ENROLL_PATH = "/v1/enroll"
    }
}
