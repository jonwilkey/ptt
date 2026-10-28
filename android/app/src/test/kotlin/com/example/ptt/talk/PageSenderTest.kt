package com.example.ptt.talk

import com.example.ptt.alert.AlertEngine
import com.example.ptt.alert.IncomingPage
import com.example.ptt.crypto.B64u
import com.example.ptt.crypto.DropReason
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.crypto.PageEnvelopes
import com.example.ptt.crypto.PagePush
import com.example.ptt.crypto.ReceiveResult
import com.example.ptt.crypto.SignedObject
import com.example.ptt.data.OutboxDao
import com.example.ptt.data.OutboxEntity
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.net.CreatePageRequest
import com.example.ptt.setup.ADMIN_USER
import com.example.ptt.setup.ISSUED_AT
import com.example.ptt.setup.USER
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val OLD = "11111111-1111-4111-8111-111111111111"
private const val NOBODY = "99999999-9999-4999-8999-999999999999"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PageSenderTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: SenderHarness
    private val f get() = h.f
    private val api get() = h.api
    private val notices get() = h.notices
    private val keys get() = h.keys
    private val roster get() = h.roster
    private val patA get() = h.patA
    private val patB get() = h.patB

    @Before
    fun setUp() {
        h = SenderHarness(tmp.newFolder())
    }

    @After
    fun tearDown() {
        h.close()
    }

    private fun TestScope.rig() = with(h) { rig() }

    private suspend fun rows() = f.db.outboxDao().all()

    private fun toSigned(request: CreatePageRequest) = SignedObject(
        B64u.decode(request.envelope.payload),
        request.envelope.signer,
        B64u.decode(request.envelope.sig)
    )

    @Test
    fun theEnvelopeWrapsExactlyTheRosterDevicesOfTheRecipient() = runTest {
        val rig = rig()
        val result = rig.sender.send(ADMIN_USER, "urgent") as SendResult.Sent
        val row = rows().single()
        assertEquals(OutboxKind.PAGE, row.kind)
        val request = Json.decodeFromString(CreatePageRequest.serializer(), row.body)
        assertEquals(result.pageId, request.id)
        assertEquals(ADMIN_USER, request.to)
        assertEquals("urgent", request.level)
        val signed = toSigned(request)
        assertEquals(SELF, signed.signer)
        val payload = EnvelopePayload.parse(signed.payload)
        assertEquals(setOf(patA.id, patB.id), payload.keys.keys)
        assertEquals(request.id, payload.pageId)
        assertEquals(request.sessionId, payload.sessionId)
        assertEquals(ISSUED_AT, payload.createdAt)
        val push = PagePush(request.id, "urgent", USER)
        val gotA = PageEnvelopes.receive(roster, patA.id, patA.keys, signed, push) as ReceiveResult.Accepted
        val gotB = PageEnvelopes.receive(roster, patB.id, patB.keys, signed, push) as ReceiveResult.Accepted
        assertArrayEquals(gotA.sessionKey, gotB.sessionKey)
        // The sender keeps the same key in memory for the audio session; nobody else in the family can open it.
        assertArrayEquals(gotA.sessionKey, keys.get(request.id))
        val outsider = PageEnvelopes.receive(roster, LEE, h.leeKeys, signed, push) as ReceiveResult.Dropped
        assertEquals(DropReason.NOT_FOR_THIS_USER, outsider.reason)
    }

    @Test
    fun theSendersRowIsStoredBeforeTheRequestIsQueued() = runTest {
        val rig = rig()
        val pageId = (rig.sender.send(ADMIN_USER, "normal") as SendResult.Sent).pageId
        val row = f.activePage.current()!!
        assertEquals(pageId, row.pageId)
        assertEquals(PageRole.SENDER, row.role)
        assertEquals(ADMIN_USER, row.peerUserId)
        assertEquals("normal", row.level)
        assertEquals("sending", row.state)
        assertEquals(listOf(OutboxKind.PAGE), rows().map { it.kind })
        runCurrent()
        assertEquals(listOf("page:$pageId"), api.calls)
    }

    @Test
    fun aRecipientWithNoDevicesSendsNothing() = runTest {
        val rig = rig()
        assertEquals(SendResult.NoDevices, rig.sender.send(NOBODY, "normal"))
        assertTrue(rows().isEmpty())
        assertNull(f.activePage.current())
    }

    @Test
    fun unusableDeviceKeysSurfaceAndSendNothing() = runTest {
        val rig = rig()
        f.keyStore.failLoadTimes = 1
        assertEquals(SendResult.KeysUnavailable, rig.sender.send(ADMIN_USER, "normal"))
        assertTrue(rows().isEmpty())
        assertNull(f.activePage.current())
    }

    @Test
    fun anUnenrolledPhoneIsNotReady() = runTest {
        val rig = rig()
        f.tokens.clear()
        assertEquals(SendResult.NotReady, rig.sender.send(ADMIN_USER, "normal"))
        assertTrue(rows().isEmpty())
    }

    // --- one page or session at a time ---

    @Test
    fun aLiveSentPageAsksFirstAndChangesNothingWithoutConfirmation() = runTest {
        val rig = rig()
        val old = (rig.sender.send(ADMIN_USER, "normal") as SendResult.Sent).pageId
        val before = rows()
        assertEquals(SendResult.Busy("Pat"), rig.sender.send(LEE_USER, "urgent"))
        assertEquals(before, rows())
        assertEquals(old, f.activePage.current()?.pageId)
        assertTrue(keys.has(old))
    }

    @Test
    fun confirmingEndsAnUnsentPageLocallyAndSendsTheNewOne() = runTest {
        val rig = rig()
        val old = (rig.sender.send(ADMIN_USER, "normal") as SendResult.Sent).pageId
        val new = (rig.sender.send(LEE_USER, "urgent", endCurrent = true) as SendResult.Sent).pageId
        assertEquals(setOf(OutboxKind.PAGE, OutboxKind.CANCEL), rows().map { it.kind }.toSet())
        assertFalse(keys.has(old))
        assertTrue(keys.has(new))
        assertEquals(new, f.activePage.current()?.pageId)
        runCurrent()
        // The first page never left the phone: ADR 0007's attempts == 0 collapse, driven from here.
        assertEquals(listOf("page:$new"), api.calls)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun confirmingEndsASentPageWithACancelAfterIt() = runTest {
        val rig = rig()
        val old = (rig.sender.send(ADMIN_USER, "normal") as SendResult.Sent).pageId
        runCurrent()
        val new = (rig.sender.send(LEE_USER, "normal", endCurrent = true) as SendResult.Sent).pageId
        runCurrent()
        assertEquals(listOf("page:$old", "cancel:$old", "page:$new"), api.calls)
    }

    @Test
    fun anAnsweredPageIsEndedWithTheAnswerersCancel() = runTest {
        val rig = rig()
        rig.engine.start(IncomingPage(OLD, "normal", ADMIN_USER, "Pat", "session-1"))
        keys.put(OLD, ByteArray(32) { 1 })
        rig.engine.answer(OLD)
        assertEquals(PageRole.RECEIVER, f.activePage.current()?.role)
        assertEquals(SendResult.Busy("Pat"), rig.sender.send(LEE_USER, "normal"))
        assertEquals(listOf(OutboxKind.ANSWER), rows().map { it.kind })
        val new = (rig.sender.send(LEE_USER, "normal", endCurrent = true) as SendResult.Sent).pageId
        assertEquals(setOf(OutboxKind.ANSWER, OutboxKind.CANCEL, OutboxKind.PAGE), rows().map { it.kind }.toSet())
        assertEquals(OLD, rows().single { it.kind == OutboxKind.CANCEL }.pageId)
        assertEquals(new, f.activePage.current()?.pageId)
        assertFalse(rig.engine.knows(OLD))
        assertFalse(keys.has(OLD))
        // Cleared when it was answered, and again when it was ended.
        assertEquals(2, notices.count("clear:$OLD"))
    }

    @Test
    fun aRingingPageIsAcknowledgedNotCancelled() = runTest {
        val rig = rig()
        rig.engine.start(IncomingPage(OLD, "normal", ADMIN_USER, "Pat", "session-1"))
        keys.put(OLD, ByteArray(32) { 1 })
        f.activePage.start(OLD, PageRole.RECEIVER, "session-1", ADMIN_USER, "normal", "ringing")
        assertEquals(SendResult.Busy("Pat"), rig.sender.send(LEE_USER, "normal"))
        assertTrue(rows().isEmpty())
        assertTrue(rig.engine.isAlerting)
        val new = (rig.sender.send(LEE_USER, "normal", endCurrent = true) as SendResult.Sent).pageId
        // Its notification is gone too: it cannot be answered over the new page later.
        assertFalse(rig.engine.knows(OLD))
        assertEquals(1, notices.count("clear:$OLD"))
        assertEquals(setOf(OutboxKind.ACKNOWLEDGE, OutboxKind.PAGE), rows().map { it.kind }.toSet())
        assertFalse(rows().any { it.kind == OutboxKind.CANCEL })
        assertFalse(rig.engine.isAlerting)
        assertEquals(new, f.activePage.current()?.pageId)
    }

    @Test
    fun aPageThatIsOverDoesNotNeedConfirmation() = runTest {
        val rig = rig()
        f.activePage.start(OLD, PageRole.SENDER, "session-1", ADMIN_USER, "normal", "failed")
        assertTrue(rig.sender.send(LEE_USER, "normal") is SendResult.Sent)
        assertEquals(listOf(OutboxKind.PAGE), rows().map { it.kind })
    }

    @Test
    fun aSendingRowOlderThanTheSendWindowIsStale() = runTest {
        val rig = rig()
        f.activePage.start(OLD, PageRole.SENDER, "session-1", ADMIN_USER, "normal", "sending")
        testScheduler.advanceTimeBy(17 * 60 * 1_000L)
        assertTrue(rig.sender.send(LEE_USER, "normal") is SendResult.Sent)
    }

    // --- a page to yourself (the Test alert) ---

    @Test
    fun aPageToYourselfWrapsForEveryDeviceAndLeavesTheKeyToTheReceiver() = runTest {
        val rig = rig()
        val result = rig.sender.sendToSelf("urgent") as SendResult.Sent
        val request = Json.decodeFromString(CreatePageRequest.serializer(), rows().single().body)
        assertEquals(USER, request.to)
        assertEquals(setOf(SELF), EnvelopePayload.parse(toSigned(request).payload).keys.keys)
        // The receive path refuses a page whose key is already held, so the sender must not hold it.
        assertFalse(keys.has(result.pageId))
        assertEquals(PageRole.SENDER, f.activePage.current()?.role)
    }

    @Test
    fun aPageThatCouldNotBeQueuedLeavesNoRowBehind() = runTest {
        val real = f.db.outboxDao()
        val failing = object : OutboxDao by real {
            override suspend fun insert(row: OutboxEntity): Long = error("disk full")
        }
        val rig = h.rig(backgroundScope, failing) { ISSUED_AT + currentTime }
        assertEquals(SendResult.Error, rig.sender.send(ADMIN_USER, "normal"))
        assertNull(f.activePage.current())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun aPageToYourselfIsRememberedForTheSenderPushes() = runTest {
        val rig = rig()
        val id = (rig.sender.sendToSelf("normal") as SendResult.Sent).pageId
        assertTrue(h.selfPages.contains(id))
        val other = (rig.sender.send(ADMIN_USER, "normal", endCurrent = true) as SendResult.Sent).pageId
        assertFalse(h.selfPages.contains(other))
    }
}
