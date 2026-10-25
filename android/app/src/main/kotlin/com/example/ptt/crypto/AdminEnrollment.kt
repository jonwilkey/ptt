package com.example.ptt.crypto

/** What the admin asked for when it minted the enrollment code. [userId] is null for a new person. */
class EnrollmentRequest(val displayName: String, val makeAdmin: Boolean, val userId: String?)

/** The `device` object of `GET /v1/enrollment-codes/{code_id}` once the code is used (binary fields decoded). */
class EnrolledDevice(val device: RosterDevice, val mac: ByteArray) {
    companion object {
        /** Strictly parses the JSON `device` object from the server response. */
        fun fromJson(value: JsonValue): EnrolledDevice {
            val o = value.asObject("enrolled device").exactly(RosterDevice.KEYS + "mac")
            val mac = B64u.decodeExact(o.string("mac"), EnrollmentMac.MAC_BYTES, "mac")
            // RosterDevice.fromJson takes the same members; the extra "mac" is split off first.
            val deviceObject = JsonValue.Obj(o.members - "mac")
            return EnrolledDevice(RosterDevice.fromJson(deviceObject), mac)
        }
    }
}

enum class EnrollmentRejection { BAD_MAC, ADMIN_MISMATCH, NAME_MISMATCH, USER_MISMATCH, USER_ALREADY_EXISTS, MALFORMED }

sealed interface EnrollmentCheck {
    /** Every check passed; [device] is ready to be added to the next roster. */
    class Accepted(val device: RosterDevice) : EnrollmentCheck
    class Rejected(val reason: EnrollmentRejection) : EnrollmentCheck
}

/** The admin-side checks of the server's enrollment-code response (docs/protocol.md section 5, step 4). */
object AdminEnrollment {
    /**
     * Verifies [device] against the admin's own [secret] and [code] and what it [request]ed. For a new
     * person (request.userId null) the user id must not already appear in [current]. For an existing
     * user the caller passes [request] with that user's id and the display name and admin flag the
     * roster already holds for them: the code inherits them (docs/protocol.md section 6), so the
     * response must echo exactly those, not whatever the admin typed. A phone that gets
     * anything but [EnrollmentCheck.Accepted] must refuse to sign a roster with this device.
     */
    fun check(
        secret: ByteArray,
        code: ByteArray,
        request: EnrollmentRequest,
        device: EnrolledDevice,
        current: Roster?
    ): EnrollmentCheck {
        val entry = device.device
        val wellFormed = try {
            entry.requireWellFormed()
            true
        } catch (_: ProtocolException) {
            false
        }
        val rejection = when {
            !wellFormed -> EnrollmentRejection.MALFORMED

            !EnrollmentMac.verify(secret, code, entry.id, entry.ed25519, entry.x25519, device.mac) ->
                EnrollmentRejection.BAD_MAC

            entry.admin != request.makeAdmin -> EnrollmentRejection.ADMIN_MISMATCH

            entry.displayName != request.displayName -> EnrollmentRejection.NAME_MISMATCH

            request.userId != null && entry.userId != request.userId -> EnrollmentRejection.USER_MISMATCH

            request.userId == null && current?.devices?.any { it.userId == entry.userId } == true ->
                EnrollmentRejection.USER_ALREADY_EXISTS

            else -> null
        }
        return if (rejection == null) EnrollmentCheck.Accepted(entry) else EnrollmentCheck.Rejected(rejection)
    }
}
