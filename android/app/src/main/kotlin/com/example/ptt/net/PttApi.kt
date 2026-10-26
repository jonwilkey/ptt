package com.example.ptt.net

/**
 * Typed client for the milestone-2 endpoints of docs/protocol.md section 6. Every call returns an
 * [ApiResult]; none throws for a network, HTTP or decoding problem.
 *
 * All calls except [enroll] use the stored server URL and fail fast with [ApiResult.NotEnrolled]
 * when the device is not enrolled (or needs re-enrollment).
 */
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

    /** `POST /v1/pages/{id}/delivered` -> 200 with the page state. */
    suspend fun delivered(pageId: String): ApiResult<DeliveredResponse>

    /** `POST /v1/pages/{id}/acknowledge` -> 204. */
    suspend fun acknowledge(pageId: String): ApiResult<Unit>

    /** `POST /v1/pages/{id}/answer` -> 204. */
    suspend fun answer(pageId: String): ApiResult<Unit>

    /** `POST /v1/pages/{id}/cancel` -> 204. */
    suspend fun cancel(pageId: String): ApiResult<Unit>
}
