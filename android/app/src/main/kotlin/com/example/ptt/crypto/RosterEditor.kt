package com.example.ptt.crypto

/**
 * Admin-side roster construction (docs/protocol.md sections 4 and 5). Every result is sorted by id,
 * has `version` one higher than its base, and is checked for well-formedness before it can be signed.
 */
object RosterEditor {
    /** Roster version 1 for the bootstrap phone: only itself, as an admin. */
    fun bootstrap(self: RosterDevice, issuedAt: Long): Roster {
        ensure(self.admin) { "the bootstrap device must be an admin" }
        return Roster(1, issuedAt, listOf(self)).also { it.requireWellFormed() }
    }

    /** [current] plus [device], as version+1. The device id must not already be listed. */
    fun addDevice(current: Roster, device: RosterDevice, issuedAt: Long): Roster {
        ensure(current.find(device.id) == null) { "device is already in the roster" }
        return next(current, issuedAt, (current.devices + device).sortedBy { it.id })
    }

    /** [current] without [deviceId], as version+1. At least one admin must remain. */
    fun removeDevice(current: Roster, deviceId: String, issuedAt: Long): Roster {
        ensure(current.find(deviceId) != null) { "device is not in the roster" }
        return next(current, issuedAt, current.devices.filter { it.id != deviceId })
    }

    /**
     * Signs [roster] as [signerId] with [keys]. The signer must be listed as an admin in [roster]
     * itself (a roster whose signer is not in it could never be accepted by a first-roster check), and
     * [keys] must be that entry's keys.
     */
    fun sign(roster: Roster, signerId: String, keys: DeviceKeys): SignedObject {
        val entry = roster.find(signerId)
        ensure(entry != null && entry.admin) { "signer is not an admin in the roster" }
        ensure(entry.ed25519.contentEquals(keys.ed25519Public)) { "signing keys do not match the roster entry" }
        return keys.signObject(Contexts.ROSTER, signerId, roster.encodePayload())
    }

    private fun next(current: Roster, issuedAt: Long, devices: List<RosterDevice>): Roster =
        Roster(current.version + 1, issuedAt, devices).also { it.requireWellFormed() }
}
