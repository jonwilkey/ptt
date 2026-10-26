package com.example.ptt.setup

import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.EnrollmentMac
import com.example.ptt.crypto.EnrollmentQr
import com.example.ptt.crypto.InMemoryDeviceKeyStore
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDevice
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import mockwebserver3.MockResponse

const val CODE_ID = "aaaaaaaaaaaaaaaaaaaaaa"

/** A phone that enrolls through an admin's QR: its keys and the server's answers about it. */
class NewPhone(
    val userId: String = UUID.randomUUID().toString(),
    val displayName: String = "Alex",
    val admin: Boolean = false,
    val id: String = UUID.randomUUID().toString()
) {
    val keys: DeviceKeys = DeviceKeys.create(InMemoryDeviceKeyStore())
    val entry get() = RosterDevice(
        id,
        userId,
        displayName,
        "$displayName phone",
        admin,
        keys.ed25519Public,
        keys.x25519Public
    )

    fun mac(secret: ByteArray, code: ByteArray): ByteArray =
        EnrollmentMac.compute(secret, code, id, keys.ed25519Public, keys.x25519Public)

    /** The `GET /v1/enrollment-codes/{id}` body for a used code. */
    fun usedJson(mac: ByteArray, overrides: Map<String, String> = emptyMap()): String {
        val fields = mutableMapOf(
            "id" to "\"$id\"",
            "user_id" to "\"$userId\"",
            "display_name" to "\"$displayName\"",
            "device_name" to "\"$displayName phone\"",
            "admin" to "$admin",
            "ed25519" to "\"${B64u.encode(keys.ed25519Public)}\"",
            "x25519" to "\"${B64u.encode(keys.x25519Public)}\"",
            "mac" to "\"${B64u.encode(mac)}\""
        )
        fields += overrides
        return """{"status":"used","device":{${fields.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""
    }
}

/** An enrolled bootstrap admin (this phone) with roster v1 stored, plus the admin tools under test. */
class AdminHarness(tmp: File) {
    val f = SetupFixture(tmp)
    var locked = false
    private val lockState = DeviceLockState { locked }
    val code = ByteArray(EnrollmentQr.CODE_BYTES) { 5 }

    val addDevice get() = AddDeviceFlow(f.api, f.sync, f.settings::serverUrl, lockState, f.clock, SecureRandom())
    val revoker get() = DeviceRevoker(f.api, f.sync, f.settings, f.tokens, lockState, f.clock)

    lateinit var selfId: String
    private var putBaseline = 0
    val keys: DeviceKeys get() = DeviceKeys.load(f.keyStore)
    val self: RosterDevice get() = f.selfEntry(admin = true)

    suspend fun enrollAsBootstrapAdmin() {
        f.serveEnroll(admin = true)
        f.fake.reply("PUT", "/v1/roster", 204)
        f.serveContacts()
        check(f.controller.enroll(f.bootstrapQr(), "Sam phone") == EnrollResult.Success)
        selfId = f.selfId()
        putBaseline = f.fake.count("PUT", "/v1/roster")
    }

    fun serveCodeCreated(codeBytes: ByteArray = code, expiresAt: Long = ISSUED_AT + 86_400_000) = f.fake.reply(
        "POST",
        "/v1/enrollment-codes",
        201,
        """{"code":"${B64u.encode(codeBytes)}","code_id":"$CODE_ID","expires_at":$expiresAt}"""
    )

    fun serveCodeStatus(body: String) = f.fake.reply("GET", "/v1/enrollment-codes/$CODE_ID", 200, body)

    /** Stores a roster signed by this admin (as if synced), replacing the server's. */
    suspend fun storeRoster(version: Long, devices: List<RosterDevice>) {
        f.serveRoster(f.signedRoster(keys, selfId, devices, version))
        f.sync.sync()
    }

    fun secretOf(pending: PendingEnrollment): ByteArray =
        EnrollmentQr.parse(pending.qrText, allowDevHttp = true).secret!!

    /** Roster PUT bodies sent after enrollment (the bootstrap PUT is not counted). */
    fun rosterPutBodies() = f.fake.bodies("PUT", "/v1/roster").drop(putBaseline)

    fun puts(): Int = rosterPutBodies().size

    fun roster(wire: String): Roster = Roster.parse(com.example.ptt.crypto.SignedObject.fromWire(wire).payload)

    fun response(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()
}
