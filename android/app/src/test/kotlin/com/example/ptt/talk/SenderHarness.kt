package com.example.ptt.talk

import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.FakeDevices
import com.example.ptt.alert.FakeNotices
import com.example.ptt.alert.FakeServiceControl
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.InMemoryDeviceKeyStore
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.data.OutboxDao
import com.example.ptt.outbox.Backoff
import com.example.ptt.outbox.FakePttApi
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.VirtualClock
import com.example.ptt.push.SelfPages
import com.example.ptt.push.SessionKeys
import com.example.ptt.setup.ISSUED_AT
import com.example.ptt.setup.SetupFixture
import com.example.ptt.setup.TestAdmin
import com.example.ptt.setup.USER
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.serialization.json.Json

const val LEE_USER = "7d2f9c10-3b8e-4a65-9f14-c0a8e5d3b762"
const val LEE = "e4a1b7c3-2d96-4f08-b5e1-6a3c9d0f7184"
const val PAT_B = "a8c2d5e9-1f47-4b30-8d6a-2e9f0b1c3475"
const val SELF = "f1b3d7a9-6c24-4e85-a0d3-9b5e8c2a1706"

/**
 * A phone that is enrolled with a verified roster, for the sending tests: this phone (user [USER]), two phones
 * of one other person ([TestAdmin]'s user), and one phone of a third. Real Room and crypto; the server is a
 * [FakePttApi] and the outbox's send loop only runs when a test calls `runCurrent`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SenderHarness(tmp: File) {
    val f = SetupFixture(tmp)
    val api = FakePttApi()
    val notices = FakeNotices()
    val keys = SessionKeys()
    val selfPages = SelfPages()
    val patA = TestAdmin()
    val patB = TestAdmin(PAT_B)
    val leeKeys = DeviceKeys.create(InMemoryDeviceKeyStore())
    val lee = RosterDevice(LEE, LEE_USER, "Lee", "Lee phone", false, leeKeys.ed25519Public, leeKeys.x25519Public)
    val self: RosterDevice
    val roster: Roster

    init {
        val own = DeviceKeys.create(f.keyStore)
        self = RosterDevice(SELF, USER, "Sam", "Sam phone", false, own.ed25519Public, own.x25519Public)
        roster = Roster(1, ISSUED_AT, listOf(self, lee, patA.device, patB.device).sortedBy { it.id })
        runBlocking {
            f.rosters.save(1, "{}", String(roster.encodePayload(), Charsets.UTF_8))
            f.settings.setEnrolled(f.url)
            f.tokens.save(SELF, "device-token")
        }
    }

    class Rig(val sender: PageSender, val engine: AlertEngine, val outbox: Outbox)

    /** Epoch plus the virtual time, so `created_at` is positive. */
    fun TestScope.rig(): Rig = rig(backgroundScope) { ISSUED_AT + currentTime }

    fun rig(scope: CoroutineScope, outboxDao: OutboxDao = f.db.outboxDao(), now: () -> Long): Rig {
        val clock = VirtualClock(now)
        val outbox = Outbox(outboxDao, api, Json, clock, { }, scope, Backoff(Random(1)))
        val engine = AlertEngine(
            scope,
            FakeDevices().devices,
            notices,
            FakeServiceControl(),
            outbox,
            f.activePage,
            keys
        )
        var counter = 0
        // Predictable ids, in the UUID shape the envelope demands.
        val ids = { "00000000-0000-4000-8000-%012d".format(++counter) }
        val sender =
            PageSender(f.sync, f.tokens, f.keyStore, outbox, f.activePage, keys, engine, notices, selfPages, clock, ids)
        return Rig(sender, engine, outbox)
    }

    fun close() = f.close()
}
