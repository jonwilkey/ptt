package com.example.ptt.net

import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** [PttApi] over OkHttp. [client] must carry the [AuthInterceptor]. */
class OkHttpPttApi(
    private val client: OkHttpClient,
    private val state: EnrollmentState,
    private val json: Json,
    private val io: CoroutineDispatcher
) : PttApi {
    override suspend fun enroll(serverUrl: String, request: EnrollRequest): ApiResult<EnrollResponse> {
        val base = serverUrl.toHttpUrlOrNull() ?: return ApiResult.Final(0, "invalid_server_url")
        val body = jsonBody(EnrollRequest.serializer(), request)
        return call(base, "POST", listOf("v1", "enroll"), body, authenticated = false) {
            decode(EnrollResponse.serializer(), it)
        }
    }

    override suspend fun createEnrollmentCode(request: CreateEnrollmentCodeRequest) = enrolledCall(
        "POST",
        listOf("v1", "enrollment-codes"),
        jsonBody(CreateEnrollmentCodeRequest.serializer(), request)
    ) {
        decode(CreateEnrollmentCodeResponse.serializer(), it)
    }

    override suspend fun getEnrollmentCode(codeId: String) =
        enrolledCall("GET", listOf("v1", "enrollment-codes", codeId), null) {
            decode(EnrollmentCodeStatusResponse.serializer(), it)
        }

    override suspend fun updateDevice(request: UpdateDeviceRequest) =
        enrolledCall("PUT", listOf("v1", "devices", "me"), jsonBody(UpdateDeviceRequest.serializer(), request)) { }

    override suspend fun revokeDevice(deviceId: String) =
        enrolledCall("POST", listOf("v1", "devices", deviceId, "revoke"), EMPTY_BODY) { }

    override suspend fun getContacts() =
        enrolledCall("GET", listOf("v1", "contacts"), null) { decode(ContactsResponse.serializer(), it) }

    override suspend fun getRoster() = enrolledCall("GET", listOf("v1", "roster"), null) {
        RosterResponse(it, decode(SignedObjectDto.serializer(), it))
    }

    override suspend fun putRoster(signedObjectJson: String) =
        enrolledCall("PUT", listOf("v1", "roster"), signedObjectJson.toRequestBody(JSON)) { }

    override suspend fun createPage(request: CreatePageRequest) =
        enrolledCall("POST", listOf("v1", "pages"), jsonBody(CreatePageRequest.serializer(), request)) {
            decode(CreatePageResponse.serializer(), it)
        }

    override suspend fun delivered(pageId: String) =
        enrolledCall("POST", listOf("v1", "pages", pageId, "delivered"), EMPTY_BODY) {
            decode(DeliveredResponse.serializer(), it)
        }

    override suspend fun acknowledge(pageId: String) = pageAction(pageId, "acknowledge")

    override suspend fun answer(pageId: String) = pageAction(pageId, "answer")

    override suspend fun cancel(pageId: String) = pageAction(pageId, "cancel")

    private suspend fun pageAction(pageId: String, action: String) =
        enrolledCall("POST", listOf("v1", "pages", pageId, action), EMPTY_BODY) { }

    private suspend fun <T> enrolledCall(
        method: String,
        segments: List<String>,
        body: RequestBody?,
        parse: (String) -> T
    ): ApiResult<T> {
        val needsReenrollment = state.needsReenrollment()
        val base = state.serverUrl()?.toHttpUrlOrNull()
        return when {
            needsReenrollment -> ApiResult.NotEnrolled(NotEnrolledReason.NEEDS_REENROLLMENT)
            base == null -> ApiResult.NotEnrolled(NotEnrolledReason.NOT_ENROLLED)
            else -> call(base, method, segments, body, authenticated = true, parse)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun <T> call(
        base: HttpUrl,
        method: String,
        segments: List<String>,
        body: RequestBody?,
        authenticated: Boolean,
        parse: (String) -> T
    ): ApiResult<T> {
        val url = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
        val request = Request.Builder().url(url).method(method, body).header("Accept", "application/json").build()
        return try {
            // `use` is outside withContext so the response is closed even if we are cancelled in between.
            client.newCall(request).await().use { response ->
                withContext(io) { mapResponse(response, authenticated, parse) }
            }
        } catch (e: NotEnrolledException) {
            ApiResult.NotEnrolled(e.reason)
        } catch (e: IOException) {
            ApiResult.Retryable(status = null, cause = e)
        }
    }

    private fun <T> mapResponse(response: Response, authenticated: Boolean, parse: (String) -> T): ApiResult<T> {
        val status = response.code
        val text = try {
            response.peekBody(MAX_BODY_BYTES).string()
        } catch (e: IOException) {
            return ApiResult.Retryable(status = null, cause = e)
        }
        return when {
            response.isSuccessful -> parseSuccess(status, text, parse)

            status == STATUS_TOO_MANY_REQUESTS || status >= STATUS_SERVER_ERROR ->
                ApiResult.Retryable(status, retryAfterMs(response))

            // Every other 4xx is final, including 401 (the interceptor has set the re-enroll flag) and
            // the page-action 409 already_answered / 410 page_ended, which the outbox treats as done.
            else -> {
                val fallback = if (authenticated && status == HTTP_UNAUTHORIZED) ErrorCode.UNAUTHORIZED else null
                ApiResult.Final(status, errorCode(text) ?: fallback)
            }
        }
    }

    private fun <T> parseSuccess(status: Int, text: String, parse: (String) -> T): ApiResult<T> = try {
        ApiResult.Success(parse(text))
    } catch (_: SerializationException) {
        ApiResult.Final(status, ErrorCode.BAD_RESPONSE)
    } catch (_: IllegalArgumentException) {
        ApiResult.Final(status, ErrorCode.BAD_RESPONSE)
    }

    private fun errorCode(text: String): String? = try {
        json.decodeFromString(ErrorBody.serializer(), text).error
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    private fun <T> jsonBody(serializer: KSerializer<T>, value: T): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(JSON)

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        const val MAX_BODY_BYTES = 1L shl 20
        const val STATUS_TOO_MANY_REQUESTS = 429
        const val STATUS_SERVER_ERROR = 500
        const val MAX_RETRY_AFTER_S = 3600L
        const val MS_PER_S = 1000L

        /** `Retry-After` in whole seconds (the only form pttd sends); anything else is ignored. */
        fun retryAfterMs(response: Response): Long? = response.header("Retry-After")?.trim()?.toLongOrNull()
            ?.coerceIn(0, MAX_RETRY_AFTER_S)?.times(MS_PER_S)
    }
}

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
