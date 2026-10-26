package com.example.ptt.setup

import com.example.ptt.crypto.AdminEnrollment
import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.EnrolledDevice
import com.example.ptt.crypto.EnrollmentCheck
import com.example.ptt.crypto.EnrollmentMac
import com.example.ptt.crypto.EnrollmentQr
import com.example.ptt.crypto.EnrollmentRejection
import com.example.ptt.crypto.EnrollmentRequest
import com.example.ptt.crypto.ProtocolException
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.crypto.RosterEditor
import com.example.ptt.crypto.TrustedAdmin
import com.example.ptt.crypto.Validation
import com.example.ptt.net.ApiResult
import com.example.ptt.net.CreateEnrollmentCodeRequest
import com.example.ptt.net.EnrolledDeviceDto
import com.example.ptt.net.NotEnrolledReason
import com.example.ptt.net.PttApi
import java.security.SecureRandom
import java.time.Clock

/** Who the new phone is for. */
sealed interface AddTarget {
    /** A new person. The name is validated like a roster display name (1 to 64 characters). */
    class NewPerson(val displayName: String, val makeAdmin: Boolean) : AddTarget

    /** Another phone for a person already in the roster; the roster's name and admin flag are inherited. */
    class ExistingUser(val userId: String) : AddTarget
}

enum class AddDeviceError {
    /** The phone's lock screen is showing. */
    LOCKED,
    NOT_ADMIN,
    INVALID_NAME,
    UNKNOWN_USER,
    NETWORK,
    REJECTED_BY_SERVER,
    NOT_ENROLLED,

    /** The new roster could not be built or the server's reply could not be verified. */
    INVALID_ROSTER,

    /** `roster_stale` even after catching up once. */
    STALE,
    KEYS_UNAVAILABLE
}

/**
 * One enrollment in progress. It holds the enrollment secret and code, so it exists only in memory for the
 * Add-device screen: it is not Parcelable or Serializable, has no persistence path and prints nothing
 * secret. [abandon] zeroes the secret and code. The [qrText] String (and any copy Compose makes of it)
 * cannot be zeroed: an accepted limit of keeping it only in memory.
 */
class PendingEnrollment internal constructor(
    val codeId: String,
    val expiresAt: Long,
    internal val request: EnrollmentRequest,
    internal val secret: ByteArray,
    internal val code: ByteArray,
    /** The exact QR text to display. It contains the secret, so it must never be stored or logged. */
    val qrText: String
) {
    fun abandon() {
        secret.fill(0)
        code.fill(0)
    }

    override fun toString() = "PendingEnrollment(codeId=$codeId, secret=<redacted>, code=<redacted>)"
}

sealed interface StartResult {
    class Started(val enrollment: PendingEnrollment) : StartResult

    class Failed(val error: AddDeviceError) : StartResult
}

sealed interface PollResult {
    data object Pending : PollResult

    /** Expired, or already cleaned up by the server (404). */
    data object Expired : PollResult

    /** The new phone enrolled; the caller must pass [device] to [AddDeviceFlow.complete]. */
    class Used(val device: EnrolledDevice) : PollResult

    /** The response could not be understood: refuse to go on with this enrollment. */
    class Rejected(val reason: EnrollmentRejection) : PollResult

    /** A network problem or 5xx/429: keep polling. */
    data object Transient : PollResult

    /** The server answered with a final error (400, 403, ...): polling can never succeed. */
    data object ServerRefused : PollResult

    data object NotEnrolled : PollResult
}

sealed interface CompleteResult {
    class Done(val device: RosterDevice) : CompleteResult

    /** The server's description of the new phone did not match what was asked for: nothing was signed. */
    class Rejected(val reason: EnrollmentRejection) : CompleteResult

    class Failed(val error: AddDeviceError) : CompleteResult
}

/**
 * The admin side of docs/protocol.md section 5, steps 1, 2 and 4: mint a code, build the QR, watch for
 * the new phone, check what the server says about it, and only then sign and publish the next roster.
 * UI-free. The enrollment secret never leaves memory and is never logged.
 */
@Suppress("LongParameterList")
class AddDeviceFlow(
    private val api: PttApi,
    private val rosterSync: RosterSync,
    private val serverUrl: suspend () -> String?,
    private val lockState: DeviceLockState,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom()
) {
    /** `POST /v1/enrollment-codes`, then the QR for it. Fails fast, without network, when refusing. */
    @Suppress("ReturnCount")
    suspend fun start(target: AddTarget): StartResult {
        if (lockState.isLocked()) return failed(AddDeviceError.LOCKED)
        val roster = rosterSync.heldAdminRoster() ?: return failed(AddDeviceError.NOT_ADMIN)
        val url = serverUrl() ?: return failed(AddDeviceError.NOT_ENROLLED)
        val admins = roster.devices.filter { it.admin }.map { TrustedAdmin(it.id, it.ed25519) }
        if (admins.isEmpty()) return failed(AddDeviceError.NOT_ADMIN)
        val (wireRequest, expected) = when (target) {
            is AddTarget.NewPerson -> {
                val name = target.displayName.trim()
                if (!Validation.isValidText(name)) return failed(AddDeviceError.INVALID_NAME)
                CreateEnrollmentCodeRequest(displayName = name, makeAdmin = target.makeAdmin) to
                    EnrollmentRequest(name, target.makeAdmin, null)
            }

            is AddTarget.ExistingUser -> {
                val known = roster.devicesOf(target.userId).firstOrNull()
                    ?: return failed(AddDeviceError.UNKNOWN_USER)
                // The code inherits the person's name and admin flag; the check below expects exactly those.
                CreateEnrollmentCodeRequest(userId = target.userId) to
                    EnrollmentRequest(known.displayName, known.admin, target.userId)
            }
        }
        val created = when (val r = api.createEnrollmentCode(wireRequest)) {
            is ApiResult.Success -> r.body
            else -> return failed(errorFor(r))
        }
        val code = try {
            B64u.decodeExact(created.code, EnrollmentQr.CODE_BYTES, "enrollment code")
        } catch (_: ProtocolException) {
            return failed(AddDeviceError.REJECTED_BY_SERVER)
        }
        val secret = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }
        val qr = try {
            EnrollmentQr(url, code, secret, admins).encode()
        } catch (_: ProtocolException) {
            secret.fill(0)
            code.fill(0)
            return failed(AddDeviceError.INVALID_ROSTER)
        }
        return StartResult.Started(PendingEnrollment(created.codeId, created.expiresAt, expected, secret, code, qr))
    }

    /** One `GET /v1/enrollment-codes/{code_id}`. */
    suspend fun poll(pending: PendingEnrollment): PollResult {
        val result = pollServer(pending)
        // The server's answer wins: a code used just before expiry must still be verified, even if polling
        // was paused past expiresAt. Local expiry only replaces "nothing happened yet" answers.
        val unanswered = result == PollResult.Pending || result == PollResult.Transient
        return if (unanswered && clock.millis() >= pending.expiresAt) PollResult.Expired else result
    }

    private suspend fun pollServer(pending: PendingEnrollment): PollResult = when (
        val r = api.getEnrollmentCode(pending.codeId)
    ) {
        is ApiResult.Success -> when (r.body.status) {
            STATUS_PENDING -> PollResult.Pending

            STATUS_EXPIRED -> PollResult.Expired

            STATUS_USED -> used(r.body.device)

            // An unknown status is the server misbehaving: treat it as not usable, never as accepted.
            else -> PollResult.Rejected(EnrollmentRejection.MALFORMED)
        }

        is ApiResult.Final -> when {
            r.status == STATUS_NOT_FOUND -> PollResult.Expired
            r.isUnauthorized -> PollResult.NotEnrolled
            else -> PollResult.ServerRefused
        }

        is ApiResult.Retryable -> PollResult.Transient

        is ApiResult.NotEnrolled -> PollResult.NotEnrolled
    }

    private fun used(dto: EnrolledDeviceDto?): PollResult {
        val device = dto?.let { toEnrolledDevice(it) } ?: return PollResult.Rejected(EnrollmentRejection.MALFORMED)
        return PollResult.Used(device)
    }

    private fun toEnrolledDevice(dto: EnrolledDeviceDto): EnrolledDevice? = try {
        val mac = B64u.decodeExact(dto.mac ?: return null, EnrollmentMac.MAC_BYTES, "mac")
        EnrolledDevice(
            RosterDevice(
                dto.id,
                dto.userId,
                dto.displayName,
                dto.deviceName,
                dto.admin,
                B64u.decode(dto.ed25519),
                B64u.decode(dto.x25519)
            ),
            mac
        )
    } catch (_: ProtocolException) {
        null
    }

    /**
     * Checks [device] with [AdminEnrollment.check] against the current roster and, only if it is accepted,
     * signs and publishes the roster that adds it. The check runs inside [RosterSync.publishEdit], so after
     * a `roster_stale` it runs again against the newer roster. Anything but Accepted signs nothing.
     */
    suspend fun complete(pending: PendingEnrollment, device: EnrolledDevice): CompleteResult {
        if (lockState.isLocked()) return CompleteResult.Failed(AddDeviceError.LOCKED)
        var rejection: EnrollmentRejection? = null
        val result = rosterSync.publishEdit { current ->
            rejection = null
            when (val check = AdminEnrollment.check(pending.secret, pending.code, pending.request, device, current)) {
                is EnrollmentCheck.Accepted -> try {
                    RosterEdit.Next(RosterEditor.addDevice(current, check.device, clock.millis()))
                } catch (_: ProtocolException) {
                    RosterEdit.Refuse(REASON_INVALID)
                }

                is EnrollmentCheck.Rejected -> {
                    rejection = check.reason
                    RosterEdit.Refuse(check.reason.name)
                }
            }
        }
        return when (result) {
            is PublishResult.Published ->
                CompleteResult.Done(result.roster.find(device.device.id) ?: device.device)

            is PublishResult.Refused -> rejection?.let { CompleteResult.Rejected(it) }
                ?: CompleteResult.Failed(AddDeviceError.INVALID_ROSTER)

            else -> CompleteResult.Failed(errorFor(result))
        }
    }

    private fun failed(error: AddDeviceError) = StartResult.Failed(error)

    private fun errorFor(result: ApiResult<*>): AddDeviceError = when (result) {
        is ApiResult.Success -> AddDeviceError.INVALID_ROSTER

        is ApiResult.Final -> when {
            result.isUnauthorized -> AddDeviceError.NOT_ENROLLED
            result.status == HTTP_FORBIDDEN -> AddDeviceError.NOT_ADMIN
            else -> AddDeviceError.REJECTED_BY_SERVER
        }

        is ApiResult.Retryable -> AddDeviceError.NETWORK

        is ApiResult.NotEnrolled ->
            if (result.reason == NotEnrolledReason.TOKEN_UNAVAILABLE) {
                AddDeviceError.KEYS_UNAVAILABLE
            } else {
                AddDeviceError.NOT_ENROLLED
            }
    }

    private fun errorFor(result: PublishResult): AddDeviceError = when (result) {
        PublishResult.NotAdmin -> AddDeviceError.NOT_ADMIN
        PublishResult.Stale -> AddDeviceError.STALE
        PublishResult.Unavailable -> AddDeviceError.NETWORK
        PublishResult.NotEnrolled -> AddDeviceError.NOT_ENROLLED
        PublishResult.KeysUnavailable -> AddDeviceError.KEYS_UNAVAILABLE
        PublishResult.ServerRejected -> AddDeviceError.REJECTED_BY_SERVER
        else -> AddDeviceError.INVALID_ROSTER
    }

    private companion object {
        const val SECRET_BYTES = 32
        const val STATUS_PENDING = "pending"
        const val STATUS_USED = "used"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_NOT_FOUND = 404
        const val HTTP_FORBIDDEN = 403
        const val REASON_INVALID = "invalid"
    }
}
