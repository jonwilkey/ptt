package com.example.ptt.alert

import androidx.test.core.app.ApplicationProvider
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.TestDb
import com.example.ptt.outbox.Backoff
import com.example.ptt.outbox.FakePttApi
import com.example.ptt.outbox.Outbox
import com.example.ptt.outbox.VirtualClock
import com.example.ptt.push.SessionKeys
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NORMAL = "11111111-1111-4111-8111-111111111111"
private const val URGENT = "22222222-2222-4222-8222-222222222222"
private const val PEER = "33333333-3333-4333-8333-333333333333"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlertEngineTest {
    private lateinit var db: PttDatabase
    private val phone = FakeDevices()
    private val notices = FakeNotices()
    private val service = FakeServiceControl()
    private val keys = SessionKeys()
    private var maxVolume = true

    @Before
    fun setUp() {
        db = TestDb.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val normal = IncomingPage(NORMAL, "normal", PEER, "Mom")
    private val urgent = IncomingPage(URGENT, "urgent", PEER, "Mom")

    private class Rig(val engine: AlertEngine, val repo: ActivePageRepository)

    private fun TestScope.rig(): Rig {
        val clock = VirtualClock { currentTime }
        // The outbox's send loop sits on a scheduler nobody advances, so queued rows stay put for the asserts.
        val parked = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))
        val outbox = Outbox(db.outboxDao(), FakePttApi(), Json, clock, { }, parked, Backoff(Random(1)))
        val repo = ActivePageRepository(db.activePageDao(), clock)
        val engine = AlertEngine(
            backgroundScope,
            phone.devices,
            notices,
            service,
            outbox,
            repo,
            keys,
            maxVolumeEnabled = { maxVolume }
        )
        return Rig(engine, repo)
    }

    private suspend fun Rig.activate(page: IncomingPage) {
        repo.start(page.pageId, PageRole.RECEIVER, "session", PEER, page.level, "ringing")
        keys.put(page.pageId, ByteArray(32) { 1 })
        engine.start(page)
    }

    // --- normal pages ---

    @Test
    fun aNormalPageNotifiesAtOnceAndRetriggersEvery30Seconds() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        runCurrent()
        assertEquals(1, notices.count("incoming:$NORMAL:normal:alerting=true"))
        assertTrue(service.running)
        assertTrue(rig.engine.isAlerting)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, notices.count("incoming:"))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, notices.count("incoming:"))
        // A normal page uses the notification (no tone, no vibration loop, no volume change).
        assertEquals(0, phone.log.events.size)
        assertEquals(3, phone.volume)
    }

    @Test
    fun aNormalPageHoldsATimedWakeLockThatIsReleasedWhenItStops() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        assertTrue(phone.wakeLockHeld)
        assertEquals(AlertEngine.NORMAL_WAKE_LOCK_MS, phone.lastWakeLockTimeoutMs)
        rig.engine.end(NORMAL)
        assertFalse(phone.wakeLockHeld)
        assertFalse(service.running)
    }

    @Test
    fun aNormalPageUnansweredFor15MinutesIsMissed() = runTest {
        val rig = rig()
        rig.activate(normal)
        runCurrent()
        advanceTimeBy(AlertEngine.MISSED_AFTER_MS - 1)
        runCurrent()
        // The first post plus a retrigger every 30 s up to (not including) the 15-minute mark.
        assertEquals(30, notices.count("incoming:"))
        assertEquals(0, notices.count("missed:"))
        advanceTimeBy(1)
        runCurrent()
        // The last retrigger and the missed timer fall on the same instant, in either order.
        val postsAtMiss = notices.count("incoming:")
        assertTrue(postsAtMiss in 30..31)
        assertEquals(listOf("missed:$NORMAL:Mom"), notices.events.filter { it.startsWith("missed:") })
        assertFalse(service.running)
        assertFalse(phone.wakeLockHeld)
        assertNull(rig.repo.current())
        assertFalse(keys.has(NORMAL))
        assertFalse(rig.engine.knows(NORMAL))
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(postsAtMiss, notices.count("incoming:"))
    }

    @Test
    fun missedIsIdempotentAndWorksForAPageThisPhoneNeverHeard() = runTest {
        val rig = rig()
        rig.activate(normal)
        rig.engine.miss(NORMAL)
        // The server's own missed push after the local timer must not blank the name.
        rig.engine.miss(NORMAL)
        assertEquals(listOf("missed:$NORMAL:Mom"), notices.events.filter { it.startsWith("missed:") })
        assertNull(rig.repo.current())
        rig.engine.miss("other", fallbackName = "Dad")
        assertEquals(1, notices.count("missed:other:Dad"))
    }

    @Test
    fun anAcknowledgedNormalPageStopsRetriggeringButStillGetsMissed() = runTest {
        val rig = rig()
        rig.activate(normal)
        runCurrent()
        rig.engine.acknowledge(NORMAL)
        val posts = notices.count("incoming:")
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals(posts, notices.count("incoming:"))
        assertFalse(rig.engine.isAlerting)
        assertTrue(rig.engine.knows(NORMAL))
        assertFalse(service.running)
        advanceTimeBy(AlertEngine.MISSED_AFTER_MS)
        runCurrent()
        assertEquals(1, notices.count("missed:$NORMAL:Mom"))
    }

    // --- urgent pages ---

    @Test
    fun anUrgentPageLoopsTheToneWithThreeSecondGapsAndNeverTimesOut() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        assertEquals(1, notices.count("incoming:$URGENT:urgent:alerting=true"))
        assertEquals(1, phone.log.count("tone"))
        assertEquals(1, phone.log.count("vibrate"))
        // 1 s tone, 3 s gap: the second tone starts at 4 s, not before.
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(1, phone.log.count("tone"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, phone.log.count("tone"))
        advanceTimeBy(3 * 60 * 60_000L)
        runCurrent()
        assertTrue(phone.log.count("tone") > 2_000)
        assertTrue(rig.engine.isAlerting)
        assertEquals(0, notices.count("missed:"))
        // The wake lock is renewed every round, so its timeout is the short one.
        assertEquals(AlertEngine.URGENT_WAKE_LOCK_MS, phone.lastWakeLockTimeoutMs)
        assertTrue(phone.wakeLockHeld)
    }

    @Test
    fun urgentSavesTheAlarmVolumeSetsMaxAndRestoresItOnEveryStopPath() = runTest {
        val stops = listOf<suspend Rig.() -> Unit>(
            { engine.acknowledge(URGENT) },
            { engine.end(URGENT) },
            { engine.answer(URGENT) },
            { engine.miss(URGENT) },
            { engine.acknowledgedElsewhere(URGENT) },
            { engine.shutdown() }
        )
        for ((index, stop) in stops.withIndex()) {
            phone.volume = 3
            phone.log.events.clear()
            val rig = rig()
            rig.engine.start(urgent)
            runCurrent()
            assertEquals("path $index sets max", phone.maxVolume, phone.volume)
            rig.stop()
            runCurrent()
            val note = "stop path $index"
            assertEquals("$note restores volume", 3, phone.volume)
            assertEquals("$note stops vibration", 1, phone.log.count("vibrate-stop"))
            assertFalse("$note releases wake lock", phone.wakeLockHeld)
            val tones = phone.log.count("tone")
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("$note stops the tone", tones, phone.log.count("tone"))
            assertFalse("$note tone not playing", phone.tonePlaying)
        }
    }

    @Test
    fun volumeIsLeftAloneWhenTheSettingIsOff() = runTest {
        maxVolume = false
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        assertEquals(3, phone.volume)
        assertEquals(0, phone.log.count("volume:"))
        rig.engine.end(URGENT)
        assertEquals(0, phone.log.count("volume:"))
    }

    @Test
    fun urgentVolumeIsRestoredToTheOriginalAcrossTwoUrgentPagesAndSurvivesTheFirstEnding() = runTest {
        val rig = rig()
        val second = IncomingPage("44444444-4444-4444-8444-444444444444", "urgent", PEER, "Dad")
        rig.engine.start(urgent)
        rig.engine.start(second)
        runCurrent()
        // Saved once, not overwritten by the already-raised volume.
        assertEquals(1, phone.log.count("volume:7"))
        rig.engine.end(URGENT)
        assertEquals(7, phone.volume)
        assertTrue(service.running)
        rig.engine.end(second.pageId)
        assertEquals(3, phone.volume)
        assertFalse(service.running)
    }

    @Test
    fun theUrgentNotificationIsPostedOnceAndNotRetriggered() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals(1, notices.count("incoming:"))
    }

    // --- several pages ---

    @Test
    fun theHighestLevelDrivesTheDeviceAndNormalRetriggersWaitWhileAnUrgentPageRings() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        runCurrent()
        assertEquals(0, phone.log.count("tone"))
        rig.engine.start(urgent)
        runCurrent()
        assertEquals(1, phone.log.count("tone"))
        assertEquals(AlertEngine.URGENT_WAKE_LOCK_MS, phone.lastWakeLockTimeoutMs)
        val normalPosts = notices.count("incoming:$NORMAL")
        advanceTimeBy(2 * 60_000)
        runCurrent()
        assertEquals(normalPosts, notices.count("incoming:$NORMAL"))
        // The urgent page ends: the normal one is still ringing and resumes its notification retriggers.
        rig.engine.end(URGENT)
        assertTrue(service.running)
        assertEquals(3, phone.volume)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(normalPosts + 1, notices.count("incoming:$NORMAL"))
        assertEquals(AlertEngine.NORMAL_WAKE_LOCK_MS, phone.lastWakeLockTimeoutMs)
    }

    @Test
    fun theServiceRunsOnlyWhileSomePageIsRinging() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        rig.engine.start(urgent)
        assertEquals(1, service.starts)
        rig.engine.end(URGENT)
        assertTrue(service.running)
        rig.engine.acknowledgedElsewhere(NORMAL)
        assertFalse(service.running)
        assertEquals(1, service.stops)
    }

    @Test
    fun aKnownPageIsNotAlertedTwice() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        rig.engine.start(normal)
        runCurrent()
        assertEquals(1, notices.count("incoming:"))
        assertEquals(1, service.starts)
    }

    // --- acknowledge and answer ---

    @Test
    fun acknowledgeStopsRingingKeepsTheNotificationAndQueuesTheServerCall() = runTest {
        val rig = rig()
        rig.activate(urgent)
        runCurrent()
        rig.engine.acknowledge(URGENT)
        runCurrent()
        assertEquals(listOf("acknowledged:$URGENT"), notices.events.filter { it.startsWith("acknowledged") })
        assertFalse(rig.engine.isAlerting)
        assertTrue(rig.engine.knows(URGENT))
        val rows = db.outboxDao().all()
        assertEquals(listOf(OutboxKind.ACKNOWLEDGE), rows.map { it.kind })
        assertEquals(URGENT, rows.single().pageId)
        assertEquals("acknowledged", rig.repo.current()?.state)
        // A second tap does nothing more.
        rig.engine.acknowledge(URGENT)
        assertEquals(1, notices.count("acknowledged:"))
    }

    @Test
    fun acknowledgeOnAPageThePhoneForgotStillTellsTheServer() = runTest {
        val rig = rig()
        rig.engine.acknowledge("55555555-5555-4555-8555-555555555555")
        runCurrent()
        assertEquals(listOf(OutboxKind.ACKNOWLEDGE), db.outboxDao().all().map { it.kind })
        assertEquals(0, notices.count("acknowledged:"))
    }

    @Test
    fun anAcknowledgementFromAnotherPhoneQuietsThisOne() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        assertTrue(rig.engine.acknowledgedElsewhere(URGENT))
        assertEquals(1, notices.count("incoming:$URGENT:urgent:alerting=false:elsewhere=true"))
        assertFalse(rig.engine.isAlerting)
        assertEquals(3, phone.volume)
        assertFalse(rig.engine.acknowledgedElsewhere("unknown"))
        assertEquals(0, db.outboxDao().all().size)
    }

    @Test
    fun answerStopsEverythingClearsTheNotificationAndQueuesTheServerCall() = runTest {
        val rig = rig()
        rig.activate(normal)
        runCurrent()
        rig.engine.answer(NORMAL)
        runCurrent()
        assertFalse(service.running)
        assertEquals(1, notices.count("clear:$NORMAL"))
        assertEquals(listOf(OutboxKind.ANSWER), db.outboxDao().all().map { it.kind })
        assertEquals("answered", rig.repo.current()?.state)
        // The session key stays for the live session (milestone 4).
        assertTrue(keys.has(NORMAL))
        assertNotNull(rig.repo.current())
    }

    @Test
    fun answeringAPageThatRangWithoutARowCreatesTheRowFromTheEnginesPage() = runTest {
        val rig = rig()
        rig.engine.start(IncomingPage(NORMAL, "normal", PEER, "Mom", "session-9"))
        rig.engine.answer(NORMAL)
        val row = rig.repo.current()!!
        assertEquals(NORMAL, row.pageId)
        assertEquals(PageRole.RECEIVER, row.role)
        assertEquals("session-9", row.sessionId)
        assertEquals(PEER, row.peerUserId)
        assertEquals("answered", row.state)
    }

    @Test
    fun answeringReplacesAnotherPagesRow() = runTest {
        val rig = rig()
        rig.repo.start(URGENT, PageRole.RECEIVER, "other", PEER, "urgent", "ringing")
        rig.engine.start(normal)
        rig.engine.answer(NORMAL)
        assertEquals(NORMAL, rig.repo.current()?.pageId)
        assertEquals("answered", rig.repo.current()?.state)
    }

    @Test
    fun answeringTwiceQueuesOneServerCall() = runTest {
        val rig = rig()
        rig.activate(normal)
        rig.engine.answer(NORMAL)
        rig.engine.answer(NORMAL)
        assertEquals(listOf(OutboxKind.ANSWER), db.outboxDao().all().map { it.kind })
    }

    @Test
    fun anAnsweredRowThatIsNotAReceiverRowDoesNotCountAsAnswered() = runTest {
        val rig = rig()
        rig.repo.start(NORMAL, PageRole.SENDER, "s", PEER, "normal", "answered")
        rig.engine.start(normal)
        rig.engine.answer(NORMAL)
        assertEquals(listOf(OutboxKind.ANSWER), db.outboxDao().all().map { it.kind })
    }

    @Test
    fun aPageNobodyKnowsIsStillAnsweredAndTheServerDecides() = runTest {
        val rig = rig()
        rig.engine.answer(NORMAL)
        assertEquals(listOf(OutboxKind.ANSWER), db.outboxDao().all().map { it.kind })
        assertNull(rig.repo.current())
    }

    @Test
    fun nameOfComesFromTheKnownPage() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        assertEquals("Mom", rig.engine.nameOf(NORMAL))
        assertNull(rig.engine.nameOf(URGENT))
    }

    // --- vibrate-only in silent mode and Do Not Disturb ---

    @Test
    fun aNormalPageVibratesItselfOnEveryRingWhenTheRingerIsSilentOrDndIsOn() = runTest {
        phone.quiet = true
        val rig = rig()
        rig.engine.start(normal)
        runCurrent()
        assertEquals(1, phone.log.count("pulse"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, phone.log.count("pulse"))
        // Acknowledging stops the retriggers, and with them the pulses.
        rig.engine.acknowledge(NORMAL)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, phone.log.count("pulse"))
    }

    @Test
    fun aNormalPageLeavesVibrationToItsChannelWhenTheRingerIsNotQuiet() = runTest {
        phone.quiet = false
        val rig = rig()
        rig.engine.start(normal)
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals(0, phone.log.count("pulse"))
    }

    @Test
    fun anUrgentPageAndAQuietReposterNeverAddAPulse() = runTest {
        phone.quiet = true
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        assertEquals(0, phone.log.count("pulse"))
        rig.engine.start(normal)
        // The normal page rings once at its start (its notification), then waits out the urgent page.
        val pulses = phone.log.count("pulse")
        advanceTimeBy(2 * 60_000)
        runCurrent()
        assertEquals(pulses, phone.log.count("pulse"))
        assertTrue(rig.engine.repost(NORMAL))
        assertEquals(pulses, phone.log.count("pulse"))
    }

    // --- wake lock ---

    @Test
    fun aSecondNormalPageTenMinutesLaterRestartsTheWakeLockTimeout() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        assertEquals(1, phone.acquires)
        advanceTimeBy(10 * 60_000)
        runCurrent()
        val second = IncomingPage("77777777-7777-4777-8777-777777777777", "normal", PEER, "Dad")
        rig.engine.start(second)
        assertEquals(2, phone.acquires)
        assertEquals(AlertEngine.NORMAL_WAKE_LOCK_MS, phone.lastWakeLockTimeoutMs)
    }

    @Test
    fun theUrgentLoopNeverTakesTheWakeLockAgainAfterItStopped() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        rig.engine.end(URGENT)
        val acquires = phone.acquires
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(acquires, phone.acquires)
        assertFalse(phone.wakeLockHeld)
        // Overtaken by a level change: the urgent renewals stop at once too.
        rig.engine.start(urgent)
        rig.engine.start(normal)
        rig.engine.acknowledge(URGENT)
        val after = phone.acquires
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(after, phone.acquires)
        assertEquals(AlertEngine.NORMAL_WAKE_LOCK_MS, phone.lastWakeLockTimeoutMs)
    }

    // --- the persisted alarm volume ---

    @Test
    fun theOriginalVolumeIsPersistedBeforeItIsRaisedAndAnEngineThatStartsLaterPutsItBack() = runTest {
        val first = rig()
        first.engine.start(urgent)
        runCurrent()
        assertEquals(3, phone.pendingVolume)
        assertEquals(phone.maxVolume, phone.volume)
        // The process dies here: nothing restores the volume. The next process builds a fresh engine.
        val second = rig()
        assertEquals(3, phone.volume)
        assertNull(phone.pendingVolume)
        second.engine.end(URGENT)
    }

    @Test
    fun nothingIsPersistedOnceTheVolumeIsRestored() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        rig.engine.end(URGENT)
        assertNull(phone.pendingVolume)
        assertEquals(3, phone.volume)
    }

    // --- repost, session id, top page ---

    @Test
    fun repostPostsTheCurrentNotificationWithoutANewSound() = runTest {
        val rig = rig()
        assertFalse(rig.engine.repost(NORMAL))
        rig.engine.start(normal)
        assertTrue(rig.engine.repost(NORMAL))
        assertEquals(1, notices.count("incoming:$NORMAL:normal:alerting=true:elsewhere=false:quiet-repost"))
        rig.engine.acknowledgedElsewhere(NORMAL)
        rig.engine.repost(NORMAL)
        assertEquals(1, notices.count("incoming:$NORMAL:normal:alerting=false:elsewhere=true:quiet-repost"))
    }

    @Test
    fun theEngineKeepsTheSessionIdAndNamesTheTopRingingPage() = runTest {
        val rig = rig()
        rig.engine.start(normal.copy(sessionId = "s-1"))
        assertEquals("s-1", rig.engine.pageOf(NORMAL)?.sessionId)
        assertEquals(NORMAL, rig.engine.topRinging()?.pageId)
        rig.engine.start(urgent)
        assertEquals(URGENT, rig.engine.topRinging()?.pageId)
        rig.engine.end(URGENT)
        rig.engine.acknowledgedElsewhere(NORMAL)
        assertNull(rig.engine.topRinging())
        assertNull(rig.engine.pageOf(URGENT))
    }

    // --- notifications that outlive a process ---

    @Test
    fun aNewEngineDropsOngoingNotificationsALostProcessLeftBehind() = runTest {
        rig()
        assertEquals(1, notices.leftoverDrops)
    }

    @Test
    fun acknowledgingAPageThisProcessDoesNotKnowRemovesItsNotificationAndStillTellsTheServer() = runTest {
        val rig = rig()
        rig.engine.acknowledge(NORMAL)
        runCurrent()
        assertEquals(1, notices.count("clear:$NORMAL"))
        assertEquals(0, notices.count("acknowledged:"))
        assertEquals(listOf(OutboxKind.ACKNOWLEDGE), db.outboxDao().all().map { it.kind })
    }

    @Test
    fun aSwipeRepostsAKnownPageAndRemovesAnUnknownOne() = runTest {
        val rig = rig()
        rig.engine.repostAfterSwipe(NORMAL)
        assertEquals(1, notices.count("clear:$NORMAL"))
        rig.engine.start(normal)
        rig.engine.repostAfterSwipe(NORMAL)
        assertEquals(1, notices.count("incoming:$NORMAL:normal:alerting=true:elsewhere=false:quiet-repost"))
        assertEquals(1, notices.count("clear:$NORMAL"))
    }

    // --- a leftover alarm volume ---

    @Test
    fun aLeftoverVolumeIsPutBackOnlyIfTheAlarmStillSitsAtMax() = runTest {
        phone.pendingVolume = 3
        phone.volume = phone.maxVolume
        rig()
        assertEquals(3, phone.volume)
        assertNull(phone.pendingVolume)
        // The user changed it after the crash: theirs stays, the marker is just cleared.
        phone.pendingVolume = 3
        phone.volume = 5
        rig()
        assertEquals(5, phone.volume)
        assertNull(phone.pendingVolume)
    }

    // --- service lifecycle ---

    @Test
    fun aLateServiceDestroyDoesNotSilenceAPageThatStartedMeanwhile() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        assertEquals(1, service.starts)
        // Page A is over and its service is being destroyed, while page B has just started ringing.
        rig.engine.end(URGENT)
        val b = IncomingPage("88888888-8888-4888-8888-888888888888", "urgent", PEER, "Dad")
        rig.engine.start(b)
        runCurrent()
        val tones = phone.log.count("tone")
        rig.engine.serviceDestroyed()
        // Still ringing, and the service is requested again.
        assertEquals(3, service.starts)
        assertEquals(phone.maxVolume, phone.volume)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(phone.log.count("tone") > tones)
        assertTrue(rig.engine.isAlerting)
    }

    @Test
    fun aServiceDestroyWithNothingRingingUndoesEverything() = runTest {
        val rig = rig()
        rig.engine.start(urgent)
        runCurrent()
        rig.engine.serviceDestroyed()
        // Nothing was acknowledged: it is ringing, so this is the restart case; now stop it for real.
        rig.engine.end(URGENT)
        rig.engine.serviceDestroyed()
        assertEquals(3, phone.volume)
        assertFalse(phone.wakeLockHeld)
    }

    @Test
    fun theForegroundNoticeFollowsTheTopRingingPage() = runTest {
        val rig = rig()
        rig.engine.start(normal)
        assertEquals(listOf<String?>(NORMAL), service.refreshes)
        rig.engine.start(urgent)
        assertEquals(listOf<String?>(NORMAL, URGENT), service.refreshes)
        rig.engine.end(URGENT)
        assertEquals(listOf<String?>(NORMAL, URGENT, NORMAL), service.refreshes)
        rig.engine.end(NORMAL)
        // Stopped: nothing to show, nothing refreshed.
        assertEquals(3, service.refreshes.size)
    }

    @Test
    fun leftoverCleanupRunsOnlyAtEngineStartNeverWhileAPageRings() = runTest {
        val rig = rig()
        assertEquals(1, notices.leftoverDrops)
        rig.engine.start(normal)
        runCurrent()
        advanceTimeBy(60_000)
        rig.engine.start(urgent)
        rig.engine.acknowledge(URGENT)
        rig.engine.end(URGENT)
        rig.engine.serviceDestroyed()
        rig.engine.acknowledgedElsewhere("unknown")
        rig.engine.repost(NORMAL)
        // Each of those recomputed the device state, but none may cancel a ringing page's notification.
        assertEquals(1, notices.leftoverDrops)
        assertTrue(rig.engine.isAlerting)
    }

    @Test
    fun aVolumeTheUserChangedMidRingStillGoesBackToTheOriginalWhenThePageStops() = runTest {
        val rig = rig()
        assertEquals(3, phone.volume)
        rig.engine.start(urgent)
        runCurrent()
        assertEquals(phone.maxVolume, phone.volume)
        phone.volume = 6
        rig.engine.end(URGENT)
        assertEquals(3, phone.volume)
        assertNull(phone.pendingVolume)
    }
}
