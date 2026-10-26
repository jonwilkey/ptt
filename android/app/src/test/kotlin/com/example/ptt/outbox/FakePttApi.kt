package com.example.ptt.outbox

import com.example.ptt.net.ApiResult
import com.example.ptt.net.ContactsResponse
import com.example.ptt.net.CreateEnrollmentCodeRequest
import com.example.ptt.net.CreateEnrollmentCodeResponse
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.net.CreatePageResponse
import com.example.ptt.net.DeliveredResponse
import com.example.ptt.net.EnrollRequest
import com.example.ptt.net.EnrollResponse
import com.example.ptt.net.EnrollmentCodeStatusResponse
import com.example.ptt.net.PttApi
import com.example.ptt.net.RosterResponse
import com.example.ptt.net.UpdateDeviceRequest

/** Scripted [PttApi] for outbox tests: results are consumed in order; the last one repeats. */
class FakePttApi : PttApi {
    val calls = mutableListOf<String>()
    val pageRequests = mutableListOf<CreatePageRequest>()
    val pageResults = ArrayDeque<ApiResult<CreatePageResponse>>()
    val deliveredResults = ArrayDeque<ApiResult<DeliveredResponse>>()
    val actionResults = ArrayDeque<ApiResult<Unit>>()

    /** Thrown (once) by the next send, to simulate an unexpected failure such as a database error. */
    var failNext: RuntimeException? = null

    /** When set, createPage suspends here (inside the "request") until completed. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private fun maybeFail() {
        failNext?.let {
            failNext = null
            throw it
        }
    }

    private fun <T> ArrayDeque<ApiResult<T>>.next(default: ApiResult<T>): ApiResult<T> =
        if (size > 1) removeFirst() else firstOrNull() ?: default

    override suspend fun createPage(request: CreatePageRequest): ApiResult<CreatePageResponse> {
        gate?.await()
        maybeFail()
        calls += "page:${request.id}"
        pageRequests += request
        return pageResults.next(ApiResult.Success(CreatePageResponse(request.id, "ringing")))
    }

    override suspend fun delivered(pageId: String): ApiResult<DeliveredResponse> {
        calls += "delivered:$pageId"
        return deliveredResults.next(ApiResult.Success(DeliveredResponse("ringing")))
    }

    override suspend fun acknowledge(pageId: String): ApiResult<Unit> = action("acknowledge", pageId)

    override suspend fun answer(pageId: String): ApiResult<Unit> = action("answer", pageId)

    override suspend fun cancel(pageId: String): ApiResult<Unit> = action("cancel", pageId)

    private fun action(name: String, pageId: String): ApiResult<Unit> {
        maybeFail()
        calls += "$name:$pageId"
        return actionResults.next(ApiResult.Success(Unit))
    }

    override suspend fun enroll(serverUrl: String, request: EnrollRequest): ApiResult<EnrollResponse> = unused()

    override suspend fun createEnrollmentCode(
        request: CreateEnrollmentCodeRequest
    ): ApiResult<CreateEnrollmentCodeResponse> = unused()

    override suspend fun getEnrollmentCode(codeId: String): ApiResult<EnrollmentCodeStatusResponse> = unused()

    override suspend fun updateDevice(request: UpdateDeviceRequest): ApiResult<Unit> = unused()

    override suspend fun revokeDevice(deviceId: String): ApiResult<Unit> = unused()

    override suspend fun getContacts(): ApiResult<ContactsResponse> = unused()

    override suspend fun getRoster(): ApiResult<RosterResponse> = unused()

    override suspend fun putRoster(signedObjectJson: String): ApiResult<Unit> = unused()

    private fun unused(): Nothing = error("not used by the outbox")
}
