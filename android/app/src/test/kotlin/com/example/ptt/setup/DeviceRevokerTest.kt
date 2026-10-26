package com.example.ptt.setup

import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceRevokerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f
    private val other = NewPhone(displayName = "Alex")
    private val otherPath get() = "/v1/devices/${other.id}/revoke"

    @Before
    fun setUp() = runBlocking {
        h = AdminHarness(tmp.newFolder())
        h.enrollAsBootstrapAdmin()
        h.storeRoster(2, listOf(h.self, other.entry))
    }

    @After
    fun tearDown() = f.close()

    private fun methodsAndPaths() = f.fake.requests.map { "${it.method} ${it.url.encodedPath}" }

    @Test
    fun theRosterIsPublishedBeforeTheDeviceIsRevoked() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("POST", otherPath, 204)

        assertEquals(RevokeResult.Done, h.revoker.remove(other.id))

        val calls = methodsAndPaths().filter { it == "PUT /v1/roster" || it == "POST $otherPath" }
        assertEquals(listOf("PUT /v1/roster", "POST $otherPath"), calls.takeLast(2))
        val published = h.roster(h.rosterPutBodies().single())
        assertEquals(3L, published.version)
        assertEquals(listOf(h.selfId), published.devices.map { it.id })
        assertEquals(3L, f.rosters.current()!!.version)
        assertTrue(f.settings.pendingRevocations().isEmpty())
    }

    @Test
    fun aFailedRevokeStaysPendingAndIsRetriedUntilItSucceeds() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("POST", otherPath, 503)
        val revoker = h.revoker

        assertEquals(RevokeResult.RevocationPending, revoker.remove(other.id))
        assertEquals(setOf(other.id), f.settings.pendingRevocations())
        assertEquals(setOf(other.id), revoker.outstanding())

        assertEquals(setOf(other.id), revoker.retryPending())
        f.fake.reply("POST", otherPath, 204)
        assertEquals(emptySet<String>(), revoker.retryPending())
        assertTrue(f.settings.pendingRevocations().isEmpty())
        assertEquals("one PUT only, retries only revoke", 1, h.puts())
    }

    @Test
    fun aPendingRevokeSurvivesARestartBecauseItIsPersisted() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("POST", otherPath, 503)
        h.revoker.remove(other.id)
        // A brand-new DeviceRevoker (a new process) finds it again and finishes it.
        f.fake.reply("POST", otherPath, 204)
        assertEquals(emptySet<String>(), h.revoker.retryPending())
        assertEquals(2, f.fake.count("POST", otherPath))
    }

    @Test
    fun anUnknownDeviceOnTheServerCountsAsRevoked() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("POST", otherPath, 404, """{"error":"not_found"}""")
        assertEquals(RevokeResult.Done, h.revoker.remove(other.id))
    }

    @Test
    fun removingThisPhoneIsRefusedWithoutAnyNetworkCall() = runBlocking {
        val before = f.fake.requests.size
        val result = h.revoker.remove(h.selfId) as RevokeResult.Refused
        assertEquals(RevokeRefusal.SELF, result.reason)
        assertEquals(before, f.fake.requests.size)
    }

    @Test
    fun anUnknownDeviceAndALockedPhoneAreRefusedWithoutANetworkCall() = runBlocking {
        val before = f.fake.requests.size
        val unknown = h.revoker.remove("44444444-4444-4444-8444-444444444444") as RevokeResult.Refused
        assertEquals(RevokeRefusal.UNKNOWN_DEVICE, unknown.reason)
        h.locked = true
        assertEquals(RevokeRefusal.LOCKED, (h.revoker.remove(other.id) as RevokeResult.Refused).reason)
        assertEquals(before, f.fake.requests.size)
    }

    @Test
    fun leavingNoAdminIsRefusedByThePlan() {
        val admin = RosterDevice(
            "55555555-5555-4555-8555-555555555555",
            USER,
            "Sam",
            "Sam phone",
            true,
            ByteArray(32) { 1 },
            ByteArray(32) { 2 }
        )
        val member = other.entry
        val roster = Roster(2, ISSUED_AT, listOf(admin, member).sortedBy { it.id })
        val plan = DeviceRevoker.plan(roster, selfId = member.id, deviceId = admin.id, issuedAt = ISSUED_AT)
        assertEquals(RevokeRefusal.LAST_ADMIN, (plan as DeviceRevoker.Plan.Refuse).reason)
        // Removing a non-admin while an admin remains is fine.
        val ok = DeviceRevoker.plan(roster, selfId = admin.id, deviceId = member.id, issuedAt = ISSUED_AT)
        assertEquals(3L, (ok as DeviceRevoker.Plan.Publish).roster.version)
    }

    @Test
    fun aFailedPublishNeverRevokesAndLeavesNothingOutstanding() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 400, """{"error":"roster_invalid"}""")
        val result = h.revoker.remove(other.id)
        assertTrue(result is RevokeResult.Failed)
        assertEquals(0, f.fake.count("POST", otherPath))
        assertTrue(f.settings.pendingRevocations().isEmpty())
        assertEquals(2L, f.rosters.current()!!.version)
    }

    @Test
    fun anAmbiguousPublishFailureNeverRevokesEvenThoughTheEntryIsKept() = runBlocking {
        f.fake.reply("PUT", "/v1/roster", 503)
        val revoker = h.revoker
        assertTrue(revoker.remove(other.id) is RevokeResult.Failed)
        // The device is still in the held roster, so the entry is not acted on (roster first).
        assertEquals(emptySet<String>(), revoker.retryPending())
        assertEquals(0, f.fake.count("POST", otherPath))
    }

    @Test
    fun aStaleRosterIsSyncedAndTheRemovalIsRedoneOnTheNewRoster() = runBlocking {
        var puts = 0
        f.fake.on("PUT", "/v1/roster") {
            if (puts++ == 0) FakeServer.response(409, """{"error":"roster_stale"}""") else FakeServer.response(204)
        }
        val third = NewPhone(displayName = "Kim")
        f.serveRoster(f.signedRoster(h.keys, h.selfId, listOf(h.self, other.entry, third.entry), 3))
        f.fake.reply("POST", otherPath, 204)

        assertEquals(RevokeResult.Done, h.revoker.remove(other.id))

        val published = h.roster(h.rosterPutBodies().last())
        assertEquals(4L, published.version)
        assertEquals(setOf(h.selfId, third.id), published.devices.map { it.id }.toSet())
    }

    @Test
    fun aStaleRetryThatFindsThisPhoneDemotedCleansUpTheEntryItWrote() = runBlocking {
        val second = TestAdmin("11111111-1111-4111-8111-111111111111")
        val demoted = RosterDevice(
            h.selfId,
            USER,
            "Sam",
            "Sam phone",
            false,
            h.keys.ed25519Public,
            h.keys.x25519Public
        )
        f.fake.reply("PUT", "/v1/roster", 409, """{"error":"roster_stale"}""")
        f.serveRoster(f.signedRoster(h.keys, h.selfId, listOf(demoted, second.device, other.entry), 3))
        val result = h.revoker.remove(other.id) as RevokeResult.Refused
        assertEquals(RevokeRefusal.NOT_ADMIN, result.reason)
        assertTrue(f.settings.pendingRevocations().isEmpty())
        assertEquals(0, f.fake.count("POST", otherPath))
    }

    @Test
    fun aNonAdminNeverRetriesRevokes() = runBlocking {
        val second = TestAdmin("11111111-1111-4111-8111-111111111111")
        val demoted = RosterDevice(
            h.selfId,
            USER,
            "Sam",
            "Sam phone",
            false,
            h.keys.ed25519Public,
            h.keys.x25519Public
        )
        f.settings.addPendingRevocation("66666666-6666-4666-8666-666666666666")
        h.storeRoster(3, listOf(demoted, second.device))
        assertEquals(emptySet<String>(), h.revoker.retryPending())
        assertEquals(0, f.fake.requests.count { it.url.encodedPath.endsWith("/revoke") })
    }

    @Test
    fun aForbiddenRevokeDropsTheEntryAndLogsIt() = runBlocking {
        val logs = mutableListOf<String>()
        val revoker = DeviceRevoker(f.api, f.sync, f.settings, f.tokens, { false }, f.clock) { logs += it }
        f.fake.reply("PUT", "/v1/roster", 204)
        f.fake.reply("POST", otherPath, 403, """{"error":"forbidden"}""")
        assertEquals(RevokeResult.ServerRefusedRevoke, revoker.remove(other.id))
        assertTrue(f.settings.pendingRevocations().isEmpty())
        assertEquals(1, logs.size)
    }

    @Test
    fun aWipeForgetsPendingRevocations() = runBlocking {
        f.settings.addPendingRevocation(other.id)
        f.settings.setLastRegistration(
            com.example.ptt.data.RegistrationValues("1", "n", null, mapOf("battery" to false))
        )
        assertEquals(mapOf("battery" to false), f.settings.lastRegistration()!!.checklist)
        f.wiper.wipeAll()
        assertTrue(f.settings.pendingRevocations().isEmpty())
        assertEquals(null, f.settings.lastRegistration())
    }
}
