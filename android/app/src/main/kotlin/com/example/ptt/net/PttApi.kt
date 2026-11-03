package com.example.ptt.net

import java.io.OutputStream

/**
 * Typed client for the milestone-2 endpoints of docs/protocol.md section 6. Every call returns an
 * [ApiResult]; none throws for a network, HTTP or decoding problem.
 *
 * All calls except [enroll] use the stored server URL and fail fast with [ApiResult.NotEnrolled]
 * when the device is not enrolled (or needs re-enrollment).
 */
@Suppress("TooManyFunctions")
interface PttApi {
    /** `POST /v1/enroll` (no auth) against [serverUrl], which is not stored yet. */
    suspend fun enroll(serverUrl: String, request: EnrollRequest): ApiResult<EnrollResponse>

    /** `POST /v1/enrollment-codes` (admin). */
    suspend fun createEnrollmentCode(request: CreateEnrollmentCodeRequest): ApiResult<CreateEnrollmentCodeResponse>

    /** `GET /v1/enrollment-codes/{code_id}` (admin). */
    suspend fun getEnrollmentCode(codeId: String): ApiResult<EnrollmentCodeStatusResponse>

    /** `PUT /v1/devices/me` -> 204. */
    suspend fun updateDevice(request: UpdateDeviceRequest): ApiResult<Unit>

    /** `POST /v1/devices/{id}/revoke` (admin) -> 204. */
    suspend fun revokeDevice(deviceId: String): ApiResult<Unit>

    /** `GET /v1/contacts`. */
    suspend fun getContacts(): ApiResult<ContactsResponse>

    /** `GET /v1/roster`: the signed object, with the body text exactly as received; 404 if none exists. */
    suspend fun getRoster(): ApiResult<RosterResponse>

    /** `PUT /v1/roster` (admin) -> 204. [signedObjectJson] is the signed object's wire JSON, sent verbatim. */
    suspend fun putRoster(signedObjectJson: String): ApiResult<Unit>

    /** `POST /v1/pages` -> 201, or 200 for an idempotent retry. */
    suspend fun createPage(request: CreatePageRequest): ApiResult<CreatePageResponse>

    /**
     * `GET /v1/pages/{id}` (sender only): the page's signed envelope and sender key, parsed strictly. 404, 410 and
     * 403 are [ApiResult.Final].
     */
    suspend fun getPage(pageId: String): ApiResult<PageRecoveryResponse>

    /** `POST /v1/pages/{id}/delivered` -> 200 with the page state. */
    suspend fun delivered(pageId: String): ApiResult<DeliveredResponse>

    /** `POST /v1/pages/{id}/acknowledge` -> 204. */
    suspend fun acknowledge(pageId: String): ApiResult<Unit>

    /** `POST /v1/pages/{id}/answer` -> 204. */
    suspend fun answer(pageId: String): ApiResult<Unit>

    /** `POST /v1/pages/{id}/cancel` -> 204. */
    suspend fun cancel(pageId: String): ApiResult<Unit>

    /** `GET /v1/app/latest` (docs/protocol.md section 14.2). 404 is [ApiResult.Final]: nothing published. */
    suspend fun getAppLatest(): ApiResult<AppLatestResponse>

    /**
     * `GET` of [downloadPath] (section 14.3), whose shape is checked here: the body is copied to [sink] and the
     * result is the number of bytes copied. More than [maxBytes] is a [ApiResult.Final] with
     * [ErrorCode.TOO_LARGE]. The call is not subject to the 30 s call timeout of the other calls.
     */
    suspend fun downloadApk(downloadPath: String, sink: OutputStream, maxBytes: Long): ApiResult<Long>
}
