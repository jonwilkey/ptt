package com.example.ptt.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Request and response bodies of docs/protocol.md section 6. The @SerialName values are the wire
 * field names, exactly. Signed payloads (roster, page envelope) are opaque here: a signed object is
 * three b64u strings that this layer only forwards.
 */

/** A signed object (protocol section 3), forwarded without looking inside `payload`. */
@Serializable
data class SignedObjectDto(val payload: String, val signer: String, val sig: String) {
    override fun toString() = "SignedObjectDto(signer=$signer, payload=<redacted>, sig=<redacted>)"
}

@Serializable
data class CreateEnrollmentCodeRequest(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("make_admin") val makeAdmin: Boolean? = null,
    @SerialName("user_id") val userId: String? = null
)

@Serializable
data class CreateEnrollmentCodeResponse(
    val code: String,
    @SerialName("code_id") val codeId: String,
    @SerialName("expires_at") val expiresAt: Long
) {
    override fun toString() = "CreateEnrollmentCodeResponse(code=<redacted>, codeId=$codeId, expiresAt=$expiresAt)"
}

@Serializable
data class EnrollmentCodeStatusResponse(val status: String, val device: EnrolledDeviceDto? = null)

@Serializable
data class EnrolledDeviceDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_name") val deviceName: String,
    val admin: Boolean,
    val ed25519: String,
    val x25519: String,
    val mac: String? = null
) {
    override fun toString() = "EnrolledDeviceDto(id=$id, userId=$userId, admin=$admin, mac=<redacted>)"
}

@Serializable
data class EnrollRequest(
    val code: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val ed25519: String,
    val x25519: String,
    /** Absent for bootstrap codes. */
    val mac: String? = null,
    /** Absent when Firebase is unavailable on this build. */
    @SerialName("fcm_token") val fcmToken: String? = null,
    @SerialName("app_version") val appVersion: String
) {
    override fun toString() = "EnrollRequest(deviceId=$deviceId, code=<redacted>, mac=<redacted>, fcmToken=<redacted>)"
}

@Serializable
data class EnrollResponse(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val admin: Boolean
) {
    override fun toString() = "EnrollResponse(token=<redacted>, deviceId=$deviceId, userId=$userId, admin=$admin)"
}

/** Every field optional; an absent field is left unchanged on the server. */
@Serializable
data class UpdateDeviceRequest(
    @SerialName("fcm_token") val fcmToken: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    val checklist: Map<String, Boolean>? = null
) {
    override fun toString() = "UpdateDeviceRequest(fcmToken=<redacted>, appVersion=$appVersion, checklist=$checklist)"
}

@Serializable
data class ContactsResponse(val users: List<ContactDto>)

@Serializable
data class ContactDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val admin: Boolean,
    /** `ok` | `warning` | `offline`. */
    val presence: String
)

@Serializable
data class CreatePageRequest(
    val id: String,
    val to: String,
    val level: String,
    @SerialName("session_id") val sessionId: String,
    val envelope: SignedObjectDto
)

/** Response of `POST /v1/pages`: `ringing`, or `ended` for a failed or already-ended page. */
@Serializable
data class CreatePageResponse(val id: String, val state: String)

/** Response of `POST /v1/pages/{id}/delivered`: `ringing`, `acknowledged` or `answered`. */
@Serializable
data class DeliveredResponse(val state: String)

/** The body of every error response. */
@Serializable
data class ErrorBody(val error: String)

/** The signed roster exactly as the server sent it, plus its parsed three fields. */
data class RosterResponse(val rawJson: String, val signed: SignedObjectDto) {
    override fun toString() = "RosterResponse(signer=${signed.signer})"
}

/** Error codes of protocol section 1 that the client branches on. */
object ErrorCode {
    const val UNAUTHORIZED = "unauthorized"
    const val PAGE_ENDED = "page_ended"
    const val ALREADY_ANSWERED = "already_answered"

    /** Not a server code: a 2xx whose body could not be read. */
    const val BAD_RESPONSE = "bad_response"
}
