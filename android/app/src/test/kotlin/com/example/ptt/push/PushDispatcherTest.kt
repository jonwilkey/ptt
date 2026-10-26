package com.example.ptt.push

import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.FakeDevices
import com.example.ptt.alert.FakeNotices
import com.example.ptt.alert.FakeServiceControl
import com.example.ptt.crypto.BuiltEnvelope
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.crypto.PageEnvelopes
import com.example.ptt.crypto.Roster
import com.example.ptt.crypto.RosterDevice
import com.example.ptt.data.ActivePageDao
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxDao
import com.example.ptt.data.OutboxEntity
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.outbox.Outbox
import com.example.ptt.setup.ADMIN_USER
import com.example.ptt.setup.ISSUED_AT
import com.example.ptt.setup.SetupFixture
import com.example.ptt.setup.TestAdmin
import com.example.ptt.setup.USER
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The push dispatcher against the real roster, crypto, outbox, Room and a MockWebServer for the server. */
@RunWith(RobolectricTestRunner::class)
class PushDispatcherTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var f: SetupFixture
    private lateinit var scope: CoroutineScope
    private val sender = TestAdmin()
    private val selfId = UUID.randomUUID().toString()
    private lateinit var roster: Roster
    private val phone = FakeDevices()
    private val notices = FakeNotices()
    private val service = FakeServiceControl()
    private val sessionKeys = SessionKeys()
    private var rosterSyncs = 0
    private lateinit var engine: AlertEngine
    private lateinit var outbox: Outbox

    @Before
    fun setUp() {
        f = SetupFixture(tmp.newFolder())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            val keys = DeviceKeys.create(f.keyStore)
            val self = RosterDevice(selfId, USER, "Sam", "Sam phone", false, keys.ed25519Public, keys.x25519Public)
            roster = Roster(1, ISSUED_AT, listOf(sender.device, self).sortedBy { it.id })
            f.rosters.save(1, "{}", String(roster.encodePayload(), Charsets.UTF_8))
            f.settings.setEnrolled(f.url)
            f.tokens.save(selfId, "device-token")
        }
        outbox = Outbox(f.db.outboxDao(), f.api, Json, f.clock, { }, scope)
        engine = AlertEngine(scope, phone.devices, notices, service, outbox, f.activePage, sessionKeys)
    }

    @After
    fun tearDown() {
        scope.cancel()
        f.close()
    }

    @Suppress("LongParameterList")
    private fun dispatcher(
        timeoutMs: Long = PushDispatcher.DELIVERED_TIMEOUT_MS,
        clock: Clock = f.clock,
        useOutbox: Outbox = outbox,
        useEngine: AlertEngine = engine,
        useActivePage: ActivePageRepository = f.activePage
    ) = PushDispatcher(
        f.sync,
        f.tokens,
        f.keyStore,
        useOutbox,
        useEngine,
        notices,
        useActivePage,
        sessionKeys,
        { rosterSyncs++ },
        clock,
        timeoutMs
    )

    private fun build(level: String = "normal", pageId: String = UUID.randomUUID().toString()): BuiltEnvelope =
        PageEnvelopes.build(
            roster,
            sender.id,
            sender.keys,
            USER,
            pageId,
            UUID.randomUUID().toString(),
            level,
            ISSUED_AT
        )

    private fun pagePush(built: BuiltEnvelope, level: String = built.payload.level) = mapOf(
        "type" to "page",
        "page_id" to built.payload.pageId,
        "level" to level,
        "from_user" to ADMIN_USER,
        "envelope" to built.signed.toWire()
    )

    private fun serveDelivered(built: BuiltEnvelope, code: Int = 200, state: String = "ringing") = f.fake.reply(
        "POST",
        "/v1/pages/${built.payload.pageId}/delivered",
        code,
        if (code == 200) """{"state":"$state"}""" else """{"error":"page_ended"}"""
    )

    private fun deliveredCalls() = f.fake.requests.count { it.url.encodedPath.endsWith("/delivered") }

    private fun outboxKinds() = runBlocking { f.db.outboxDao().all().map { it.kind } }

    // --- the receive flow ---

    @Test
    fun ringingDeliveredAlertsStoresTheActivePageAndKeepsTheKeyInMemory() = runBlocking {
        val built = build("urgent")
        serveDelivered(built)
        dispatcher().dispatch(pagePush(built))
        val id = built.payload.pageId
        assertEquals(1, deliveredCalls())
        assertEquals(listOf("incoming:$id:urgent:alerting=true:elsewhere=false"), notices.events)
        assertTrue(service.running)
        assertEquals("Pat", engine.nameOf(id))
        val row = f.activePage.current()!!
        assertEquals(PageRole.RECEIVER, row.role)
        assertEquals("ringing", row.state)
        assertEquals("urgent", row.level)
        assertEquals(ADMIN_USER, row.peerUserId)
        assertEquals(built.payload.sessionId, row.sessionId)
        assertArrayEquals(built.sessionKey, sessionKeys.get(id))
        assertTrue(outboxKinds().isEmpty())
    }

    @Test
    fun theCallerNameComesFromTheRosterNotThePush() = runBlocking {
        val built = build()
        serveDelivered(built)
        dispatcher().dispatch(pagePush(built) + mapOf("from_name" to "Mallory", "display_name" to "Mallory"))
        assertEquals("Pat", engine.nameOf(built.payload.pageId))
    }

    @Test
    fun aDroppedEnvelopeMakesNoDeliveredCallAndNoAlert() = runBlocking {
        val built = build()
        serveDelivered(built)
        val other = build(pageId = UUID.randomUUID().toString())
        val unknown = TestAdmin("aaaaaaaa-5d2b-4b84-a6e0-9f1c8d3b2a57")
        val foreign = PageEnvelopes.build(
            Roster(2, ISSUED_AT, (roster.devices + unknown.device).sortedBy { it.id }),
            unknown.id,
            unknown.keys,
            USER,
            built.payload.pageId,
            built.payload.sessionId,
            "normal",
            ISSUED_AT
        )
        val bad = listOf(
            pagePush(built, level = "urgent"),
            pagePush(built) + ("from_user" to USER),
            pagePush(built) + ("page_id" to other.payload.pageId),
            pagePush(built) + ("envelope" to "{not json"),
            pagePush(built) - "envelope",
            pagePush(built) - "from_user",
            pagePush(foreign)
        )
        for (push in bad) dispatcher().dispatch(push)
        assertEquals(0, deliveredCalls())
        assertTrue(notices.events.isEmpty())
        assertFalse(service.running)
        assertNull(f.activePage.current())
        assertFalse(sessionKeys.has(built.payload.pageId))
        assertTrue(outboxKinds().isEmpty())
    }

    @Test
    fun aPhoneWithoutARosterOrEnrollmentDropsPages() = runBlocking {
        val built = build()
        serveDelivered(built)
        f.rosters.clear()
        dispatcher().dispatch(pagePush(built))
        f.tokens.clear()
        dispatcher().dispatch(pagePush(built))
        assertEquals(0, deliveredCalls())
        assertTrue(notices.events.isEmpty())
    }

    @Test
    fun anAcknowledgedPageGetsASilentAnswerableNotificationAndNoRinging() = runBlocking {
        val built = build("urgent")
        serveDelivered(built, state = "acknowledged")
        dispatcher().dispatch(pagePush(built))
        val id = built.payload.pageId
        assertEquals(listOf("incoming:$id:urgent:alerting=false:elsewhere=true"), notices.events)
        assertFalse(service.running)
        assertEquals(0, phone.log.events.size)
        assertEquals("acknowledged", f.activePage.current()?.state)
        assertTrue(sessionKeys.has(id))
        // Still known, so a later page_cancel clears it.
        assertTrue(engine.knows(id))
    }

    @Test
    fun aPageThisDeviceAlreadyAnsweredShowsAnsweredWithoutRinging() = runBlocking {
        val built = build()
        serveDelivered(built, state = "answered")
        dispatcher().dispatch(pagePush(built))
        assertEquals(listOf("answered:${built.payload.pageId}"), notices.events)
        assertFalse(service.running)
        assertEquals("answered", f.activePage.current()?.state)
    }

    @Test
    fun aPageThatEndedDropsSilentlyOnA410() = runBlocking {
        val built = build()
        serveDelivered(built, code = 410)
        dispatcher().dispatch(pagePush(built))
        assertEquals(1, deliveredCalls())
        assertTrue(notices.events.isEmpty())
        assertFalse(service.running)
        assertNull(f.activePage.current())
        assertFalse(sessionKeys.has(built.payload.pageId))
        assertTrue(outboxKinds().isEmpty())
    }

    @Test
    fun aNetworkFailureAlertsAnywayAndQueuesDeliveredInTheOutbox() = runBlocking {
        val built = build()
        serveDelivered(built, code = 503)
        dispatcher().dispatch(pagePush(built))
        assertEquals(1, notices.count("incoming:${built.payload.pageId}:normal:alerting=true"))
        assertTrue(service.running)
        assertEquals(listOf(OutboxKind.DELIVERED), outboxKinds())
        assertNotNull(f.activePage.current())
    }

    @Test
    fun aSlowServerIsGivenAboutThreeSecondsThenWeAlertAndQueueDelivered() = runBlocking {
        val built = build("urgent")
        f.fake.on("POST", "/v1/pages/${built.payload.pageId}/delivered") {
            MockResponse.Builder().code(200).body("""{"state":"ringing"}""")
                .headersDelay(10, TimeUnit.SECONDS).build()
        }
        val started = System.nanoTime()
        dispatcher(timeoutMs = 400).dispatch(pagePush(built))
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("took $tookMs ms", tookMs < 3_000)
        assertEquals(1, notices.count("incoming:${built.payload.pageId}:urgent:alerting=true"))
        assertTrue(service.running)
        // Cancelling the request skipped Outbox.deliver's own queueing, so the dispatcher queued the row.
        assertEquals(listOf(OutboxKind.DELIVERED), outboxKinds())
    }

    @Test
    fun aRePushOfAKnownPageDoesNotAlertOrDeliverTwice() = runBlocking {
        val built = build()
        serveDelivered(built)
        val d = dispatcher()
        d.dispatch(pagePush(built))
        d.dispatch(pagePush(built))
        assertEquals(1, deliveredCalls())
        // The second push only re-posts the existing notification, without a new sound.
        assertEquals(2, notices.count("incoming:"))
        assertEquals(1, notices.count("incoming:${built.payload.pageId}:normal:alerting=true:elsewhere=false:quiet"))
        assertEquals(1, service.starts)
    }

    // --- a second page while one is active ---

    @Test
    fun aSecondPageRingsAndNotifiesButAnUrgentOneTakesTheActiveRowFromANormalOne() = runBlocking {
        val first = build("normal")
        val second = build("urgent")
        serveDelivered(first)
        serveDelivered(second)
        val d = dispatcher()
        d.dispatch(pagePush(first))
        assertEquals(first.payload.pageId, f.activePage.current()?.pageId)
        d.dispatch(pagePush(second))
        assertEquals(second.payload.pageId, f.activePage.current()?.pageId)
        assertTrue(engine.knows(first.payload.pageId))
        assertTrue(engine.knows(second.payload.pageId))
        assertEquals(1, service.starts)
        assertTrue(sessionKeys.has(first.payload.pageId))
    }

    @Test
    fun aSecondNormalPageNeverDisplacesTheActiveOneOrAnUrgentOne() = runBlocking {
        val urgent = build("urgent")
        val normalA = build("normal")
        val normalB = build("normal")
        for (b in listOf(urgent, normalA, normalB)) serveDelivered(b)
        val d = dispatcher()
        d.dispatch(pagePush(urgent))
        d.dispatch(pagePush(normalA))
        assertEquals(urgent.payload.pageId, f.activePage.current()?.pageId)
        f.activePage.end(urgent.payload.pageId)
        f.activePage.start(normalA.payload.pageId, PageRole.RECEIVER, "s", ADMIN_USER, "normal", "ringing")
        d.dispatch(pagePush(normalB))
        assertEquals(normalA.payload.pageId, f.activePage.current()?.pageId)
        assertTrue(engine.knows(normalB.payload.pageId))
    }

    @Test
    fun anAnsweredSessionOrAnOutgoingPageKeepsTheRowButStillRings() = runBlocking {
        val incoming = build("urgent")
        serveDelivered(incoming)
        f.activePage.start("sent-page", PageRole.SENDER, "s", ADMIN_USER, "normal", "ringing")
        dispatcher().dispatch(pagePush(incoming))
        assertEquals("sent-page", f.activePage.current()?.pageId)
        assertTrue(engine.isAlerting)
        f.activePage.start("answered-page", PageRole.RECEIVER, "s", ADMIN_USER, "normal", "answered")
        sessionKeys.put("answered-page", ByteArray(32))
        val next = build("urgent")
        serveDelivered(next)
        dispatcher().dispatch(pagePush(next))
        assertEquals("answered-page", f.activePage.current()?.pageId)
        assertTrue(engine.knows(next.payload.pageId))
    }

    @Test
    fun anEndedRowIsReplacedByTheNextPage() = runBlocking {
        f.activePage.start("old", PageRole.SENDER, "s", ADMIN_USER, "normal", "ended")
        val built = build()
        serveDelivered(built)
        dispatcher().dispatch(pagePush(built))
        assertEquals(built.payload.pageId, f.activePage.current()?.pageId)
    }

    // --- the other messages ---

    private suspend fun ringingPage(level: String = "normal"): BuiltEnvelope {
        val built = build(level)
        serveDelivered(built)
        dispatcher().dispatch(pagePush(built))
        notices.events.clear()
        return built
    }

    @Test
    fun cancelledStopsAlertingReplacesTheNotificationAndClearsTheActivePage() = runBlocking {
        val id = ringingPage().payload.pageId
        dispatcher().dispatch(mapOf("type" to "page_cancel", "page_id" to id, "reason" to "cancelled"))
        assertEquals(listOf("cancelled:$id"), notices.events)
        assertFalse(service.running)
        assertFalse(engine.knows(id))
        assertNull(f.activePage.current())
        assertFalse(sessionKeys.has(id))
    }

    @Test
    fun answeredElsewhereShowsItsOwnShortNotification() = runBlocking {
        val id = ringingPage("urgent").payload.pageId
        dispatcher().dispatch(mapOf("type" to "page_cancel", "page_id" to id, "reason" to "answered_elsewhere"))
        assertEquals(listOf("answered-elsewhere:$id"), notices.events)
        assertFalse(service.running)
        assertEquals(3, phone.volume)
        assertNull(f.activePage.current())
    }

    @Test
    fun missedLeavesTheMissedNotificationWithTheNameFromTheRoster() = runBlocking {
        val id = ringingPage().payload.pageId
        dispatcher().dispatch(mapOf("type" to "page_cancel", "page_id" to id, "reason" to "missed"))
        assertEquals(listOf("missed:$id:Pat"), notices.events)
        assertFalse(service.running)
        assertNull(f.activePage.current())
        assertFalse(sessionKeys.has(id))
        // The local 15-minute timer and the server's push are both safe to repeat.
        dispatcher().dispatch(mapOf("type" to "page_cancel", "page_id" to id, "reason" to "missed"))
        assertEquals(1, notices.count("missed:"))
    }

    @Test
    fun missedForAPageNotHeldStillNamesTheCallerFromTheActiveRow() = runBlocking {
        f.activePage.start("p-9", PageRole.RECEIVER, "s", ADMIN_USER, "normal", "ringing")
        dispatcher().dispatch(mapOf("type" to "page_cancel", "page_id" to "p-9", "reason" to "missed"))
        assertEquals(listOf("missed:p-9:Pat"), notices.events)
    }

    @Test
    fun anUnknownCancelReasonAndAnUnknownPageStillChangeTheNotification() = runBlocking {
        dispatcher().dispatch(mapOf("type" to "page_cancel", "page_id" to "gone", "reason" to "weird"))
        assertEquals(listOf("cancelled:gone"), notices.events)
    }

    @Test
    fun anAcknowledgementElsewhereSilencesTheRingButKeepsTheNotification() = runBlocking {
        val id = ringingPage("urgent").payload.pageId
        dispatcher().dispatch(mapOf("type" to "page_ack", "page_id" to id))
        assertEquals(listOf("incoming:$id:urgent:alerting=false:elsewhere=true"), notices.events)
        assertFalse(service.running)
        assertEquals(3, phone.volume)
        assertEquals("acknowledged", f.activePage.current()?.state)
        assertTrue(engine.knows(id))
        assertTrue(sessionKeys.has(id))
    }

    @Test
    fun anAcknowledgementForAnUnknownPageStillShowsSomething() = runBlocking {
        dispatcher().dispatch(mapOf("type" to "page_ack", "page_id" to "nobody"))
        assertEquals(listOf("ack-unknown:nobody"), notices.events)
    }

    private fun senderRow(pageId: String): ActivePageEntity = runBlocking {
        f.activePage.start(pageId, PageRole.SENDER, "s", ADMIN_USER, "normal", "ringing")
        f.activePage.current()!!
    }

    @Test
    fun theSenderIsToldWhoAnsweredFromTheRosterAndTheStateIsUpdated() = runBlocking {
        senderRow("sent-1")
        val events = mutableListOf<PageEvent>()
        val d = dispatcher()
        val job = launch(Dispatchers.Unconfined) { d.events.toList(events) }
        d.dispatch(mapOf("type" to "page_answered", "page_id" to "sent-1", "name" to "Mallory"))
        job.cancel()
        assertEquals(listOf("sender-answered:sent-1:Pat"), notices.events)
        assertEquals("answered", f.activePage.current()?.state)
        assertEquals(listOf<PageEvent>(PageEvent.Answered("sent-1")), events)
    }

    @Test
    fun pageStateUpdatesTheRowAndEmitsEventsWithShortNotificationsOnlyForFailedAndMissed() = runBlocking {
        senderRow("sent-2")
        val events = mutableListOf<PageEvent>()
        val d = dispatcher()
        val job = launch(Dispatchers.Unconfined) { d.events.toList(events) }
        for (state in listOf("delivered", "acknowledged", "not_delivered")) {
            d.dispatch(mapOf("type" to "page_state", "page_id" to "sent-2", "state" to state))
            assertEquals(state, f.activePage.current()?.state)
        }
        assertTrue(notices.events.isEmpty())
        d.dispatch(mapOf("type" to "page_state", "page_id" to "sent-2", "state" to "bogus"))
        d.dispatch(mapOf("type" to "page_state", "page_id" to "sent-2"))
        assertEquals("not_delivered", f.activePage.current()?.state)
        for (state in listOf("failed", "missed", "ended")) {
            senderRow("sent-2")
            d.dispatch(mapOf("type" to "page_state", "page_id" to "sent-2", "state" to state))
            // A page that is over leaves no row to block the next one.
            assertNull(state, f.activePage.current())
        }
        job.cancel()
        assertEquals(listOf("sender-state:sent-2:failed:Pat", "sender-state:sent-2:missed:Pat"), notices.events)
        assertEquals(
            listOf("delivered", "acknowledged", "not_delivered", "failed", "missed", "ended"),
            events.map { (it as PageEvent.State).state }
        )
    }

    // --- which rows a new page may replace ---

    @Test
    fun aSenderRowInATerminalStateDoesNotBlockTheNextIncomingPage() = runBlocking {
        for (state in listOf("missed", "failed", "ended")) {
            f.activePage.clear()
            f.activePage.start("sent-old-$state", PageRole.SENDER, "s", ADMIN_USER, "normal", state)
            val built = build()
            serveDelivered(built)
            dispatcher().dispatch(pagePush(built))
            assertEquals(state, built.payload.pageId, f.activePage.current()?.pageId)
        }
    }

    @Test
    fun anAnsweredRowIsReplacedOnlyOnceItIsOlderThanASession() = runBlocking {
        f.activePage.start("answered-old", PageRole.RECEIVER, "s", ADMIN_USER, "normal", "answered")
        sessionKeys.put("answered-old", ByteArray(32))
        val fresh = build()
        serveDelivered(fresh)
        dispatcher().dispatch(pagePush(fresh))
        assertEquals("answered-old", f.activePage.current()?.pageId)
        val later = Clock.offset(f.clock, Duration.ofMinutes(20))
        val stale = build()
        serveDelivered(stale)
        dispatcher(clock = later).dispatch(pagePush(stale))
        assertEquals(stale.payload.pageId, f.activePage.current()?.pageId)
    }

    @Test
    fun aReceiverRowThisProcessKnowsNothingOfIsAnOrphanAndIsReplaced() = runBlocking {
        f.activePage.start("orphan", PageRole.RECEIVER, "s", ADMIN_USER, "urgent", "ringing")
        val built = build()
        serveDelivered(built)
        dispatcher().dispatch(pagePush(built))
        assertEquals(built.payload.pageId, f.activePage.current()?.pageId)
        // With a session key held, the same kind of row is still a live session.
        f.activePage.start("live", PageRole.RECEIVER, "s", ADMIN_USER, "urgent", "ringing")
        sessionKeys.put("live", ByteArray(32))
        val next = build()
        serveDelivered(next)
        dispatcher().dispatch(pagePush(next))
        assertEquals("live", f.activePage.current()?.pageId)
    }

    @Test
    fun aPageThatRangWithoutARowKeepsItsSessionIdInTheEngine() = runBlocking {
        f.activePage.start("sent-busy", PageRole.SENDER, "s", ADMIN_USER, "normal", "ringing")
        val built = build("urgent")
        serveDelivered(built)
        dispatcher().dispatch(pagePush(built))
        assertEquals("sent-busy", f.activePage.current()?.pageId)
        val kept = engine.pageOf(built.payload.pageId)!!
        assertEquals(built.payload.sessionId, kept.sessionId)
        assertEquals("urgent", kept.level)
        assertEquals(ADMIN_USER, kept.fromUserId)
    }

    // --- alerting does not depend on Room ---

    private class FailingActivePageDao : ActivePageDao {
        override fun observe(): Flow<ActivePageEntity?> = emptyFlow()

        override suspend fun get(): ActivePageEntity? = error("db down")

        override suspend fun insert(page: ActivePageEntity) = error("db down")

        override suspend fun update(page: ActivePageEntity): Int = error("db down")

        override suspend fun clear() = error("db down")

        override suspend fun delete(pageId: String) = error("db down")
    }

    private class FailingOutboxDao : OutboxDao {
        override suspend fun insert(row: OutboxEntity): Long = error("db down")

        override suspend fun all(): List<OutboxEntity> = error("db down")

        override suspend fun get(id: String): OutboxEntity? = error("db down")

        override fun observeCount(): Flow<Int> = emptyFlow()

        override suspend fun reschedule(id: String, attempts: Int, nextAttemptAt: Long) = error("db down")

        override suspend fun delete(id: String) = error("db down")

        override suspend fun clear() = error("db down")
    }

    @Test
    fun aFailingDatabaseNeverStopsAPageFromAlerting() = runBlocking {
        val brokenRows = ActivePageRepository(FailingActivePageDao(), f.clock)
        val brokenOutbox = Outbox(FailingOutboxDao(), f.api, Json, f.clock, { }, scope)
        val brokenEngine = AlertEngine(
            scope,
            phone.devices,
            notices,
            service,
            brokenOutbox,
            brokenRows,
            sessionKeys
        )
        // 503: Outbox.deliver tries to queue its retry and fails, then so does the dispatcher's own queueing.
        val built = build("urgent")
        serveDelivered(built, code = 503)
        val d = dispatcher(useOutbox = brokenOutbox, useEngine = brokenEngine, useActivePage = brokenRows)
        d.dispatch(pagePush(built))
        val id = built.payload.pageId
        assertEquals(1, notices.count("incoming:$id:urgent:alerting=true"))
        assertTrue(service.running)
        assertTrue(brokenEngine.isAlerting)
        // Acknowledge and cancel still do their local part.
        brokenEngine.acknowledge(id)
        assertFalse(brokenEngine.isAlerting)
        assertEquals(1, notices.count("acknowledged:$id"))
        d.dispatch(mapOf("type" to "page_cancel", "page_id" to id, "reason" to "cancelled"))
        assertEquals(1, notices.count("cancelled:$id"))
        assertFalse(sessionKeys.has(id))
    }

    @Test
    fun aRosterPushSchedulesASyncAndTestPostsTheTestNotification() = runBlocking {
        val d = dispatcher()
        d.dispatch(mapOf("type" to "roster", "version" to "7"))
        assertEquals(1, rosterSyncs)
        assertEquals(0, f.fake.requests.size)
        d.dispatch(mapOf("type" to "test"))
        assertEquals(listOf("test"), notices.events)
    }

    @Test
    fun checkInAndUnknownTypesDoNothing() = runBlocking {
        val d = dispatcher()
        d.dispatch(mapOf("type" to "check_in"))
        d.dispatch(mapOf("type" to "from_the_future", "page_id" to "x"))
        d.dispatch(emptyMap())
        assertTrue(notices.events.isEmpty())
        assertEquals(0, rosterSyncs)
        assertEquals(0, f.fake.requests.size)
    }

    @Test
    fun aMessageWithoutAPageIdIsIgnored() = runBlocking {
        val d = dispatcher()
        for (type in listOf("page_cancel", "page_ack", "page_answered", "page_state")) {
            d.dispatch(mapOf("type" to type))
        }
        assertTrue(notices.events.isEmpty())
    }
}
