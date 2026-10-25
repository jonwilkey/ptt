package com.example.ptt.crypto

/** One roster entry (docs/protocol.md section 4). Keys are the raw 32-byte public keys. */
// The constructor mirrors the protocol's fixed seven-field device entry.
@Suppress("LongParameterList")
class RosterDevice(
    val id: String,
    val userId: String,
    val displayName: String,
    val deviceName: String,
    val admin: Boolean,
    val ed25519: ByteArray,
    val x25519: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is RosterDevice &&
        id == other.id && userId == other.userId && displayName == other.displayName &&
        deviceName == other.deviceName && admin == other.admin &&
        ed25519.contentEquals(other.ed25519) && x25519.contentEquals(other.x25519)

    override fun hashCode(): Int = id.hashCode()

    internal fun toJson(): String =
        """{"id":"$id","user_id":"$userId","display_name":${JsonText.quote(displayName)},""" +
            """"device_name":${JsonText.quote(deviceName)},"admin":$admin,""" +
            """"ed25519":"${B64u.encode(ed25519)}","x25519":"${B64u.encode(x25519)}"}"""

    internal fun requireWellFormed() {
        Validation.requireUuid(id, "device id")
        Validation.requireUuid(userId, "user id")
        Validation.requireText(displayName, "display_name")
        Validation.requireText(deviceName, "device_name")
        ensure(ed25519.size == Validation.KEY_BYTES && x25519.size == Validation.KEY_BYTES) { "bad key length" }
    }

    internal companion object {
        val KEYS = setOf("id", "user_id", "display_name", "device_name", "admin", "ed25519", "x25519")

        fun fromJson(value: JsonValue): RosterDevice {
            val o = value.asObject("roster device").exactly(KEYS)
            val device = RosterDevice(
                id = o.string("id"),
                userId = o.string("user_id"),
                displayName = o.string("display_name"),
                deviceName = o.string("device_name"),
                admin = o.bool("admin"),
                ed25519 = B64u.decode(o.string("ed25519")),
                x25519 = B64u.decode(o.string("x25519"))
            )
            device.requireWellFormed()
            return device
        }
    }
}

/**
 * A family roster payload (docs/protocol.md section 4). [devices] are sorted by id. Build one with
 * [parse] (from signed bytes) or [RosterEditor]; both enforce the same well-formedness rules.
 */
class Roster(val version: Long, val issuedAt: Long, val devices: List<RosterDevice>) {
    fun find(deviceId: String): RosterDevice? = devices.firstOrNull { it.id == deviceId }

    fun devicesOf(userId: String): List<RosterDevice> = devices.filter { it.userId == userId }

    /** The exact payload bytes to sign. Requires a well-formed roster. */
    fun encodePayload(): ByteArray {
        requireWellFormed()
        val list = devices.joinToString(",") { it.toJson() }
        return """{"format":$FORMAT,"version":$version,"issued_at":$issuedAt,"devices":[$list]}"""
            .toByteArray(Charsets.UTF_8)
    }

    /** Throws [ProtocolException] unless this is a valid roster per section 4. */
    fun requireWellFormed() {
        ensure(version > 0 && issuedAt > 0) { "roster version and issued_at must be positive" }
        devices.forEachIndexed { i, d ->
            d.requireWellFormed()
            ensure(i == 0 || d.id > devices[i - 1].id) { "roster devices must be sorted by id, without duplicates" }
        }
        ensure(devices.any { it.admin }) { "roster has no admin device" }
    }

    companion object {
        const val FORMAT = 1L

        /** Strictly parses a roster payload; throws [ProtocolException] for anything section 3 or 4 forbids. */
        fun parse(payload: ByteArray): Roster {
            val top = StrictJson.parse(payload).asObject("roster")
                .exactly("format", "version", "issued_at", "devices")
            ensure(top.number("format") == FORMAT) { "unsupported roster format" }
            val roster = Roster(
                version = top.number("version"),
                issuedAt = top.number("issued_at"),
                devices = top.array("devices").map { RosterDevice.fromJson(it) }
            )
            roster.requireWellFormed()
            return roster
        }
    }
}
