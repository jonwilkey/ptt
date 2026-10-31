package com.example.ptt.audio

import com.example.ptt.net.SessionEndReason
import com.example.ptt.talk.IncomingOpus
import com.example.ptt.talk.SessionStage
import com.example.ptt.talk.TalkConnection
import com.example.ptt.talk.TalkEvent
import com.example.ptt.talk.TalkFloor
import com.example.ptt.talk.TalkSessionState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAGE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

private class FakeLink(private val sent: SentLog) : TalkLink {
    override val state = MutableStateFlow(TalkSessionState())
    override val events = MutableSharedFlow<TalkEvent>(extraBufferCapacity = 64)
    override val incomingOpus = MutableSharedFlow<IncomingOpus>(extraBufferCapacity = 64)
    var pressResult = true
    val presses = AtomicInteger()
    val releases = AtomicInteger()

    override fun pressTalk(): Boolean {
        presses.incrementAndGet()
        return pressResult
    }

    override fun releaseTalk() {
        releases.incrementAndGet()
    }

    override fun sendOpus(packet: ByteArray) = sent.send(packet)

    fun set(
        floor: TalkFloor = state.value.floor,
        connection: TalkConnection = state.value.connection,
        stage: SessionStage = state.value.stage,
        pageId: String? = state.value.pageId
    ) {
        state.value = state.value.copy(floor = floor, connection = connection, stage = stage, pageId = pageId)
    }
}

private class TaDecoder : OpusDecoder {
    var closed = false

    override fun decode(opus: ByteArray) = DecodedPcm(ShortArray(4) { opus[0].toShort() }, 48_000)

    override fun close() {
        closed = true
    }
}

private class TaSink : AudioSink {
    val writes = CopyOnWriteArrayList<Int>()

    override fun start() = Unit

    override fun stop() = Unit

    override fun flush() = Unit

    override fun write(pcm: ShortArray) {
        writes += pcm[0].toInt()
    }

    override fun close() = Unit
}

class TalkAudioTest {
    private val sent = SentLog()
    private val link = FakeLink(sent)
    private val platform = FakePlatform()
    private val sources = CopyOnWriteArrayList<FakeSource>()
    private val decoders = CopyOnWriteArrayList<TaDecoder>()
    private val sinks = CopyOnWriteArrayList<TaSink>()
    private val fastClock = AtomicLong()
    private var useFastClock = false
    private val audio = TalkAudio(
        pageId = PAGE,
        link = link,
        sourceFactory = {
            FakeSource(if (useFastClock) fastClock else null, if (useFastClock) 0 else 1).also {
                sources +=
                    it
            }
        },
        encoderFactory = { FakeEncoder() },
        decoderFactory = { TaDecoder().also { decoders += it } },
        sinkFactory = { TaSink().also { sinks += it } },
        platform = platform,
        clock = { if (useFastClock) fastClock.get() else System.currentTimeMillis() },
        dispatcher = Dispatchers.Unconfined
    )

    @After
    fun tearDown() = audio.close()

    private fun conversation(floor: TalkFloor = TalkFloor.IDLE) = link.set(
        floor = floor,
        connection = TalkConnection.LIVE,
        stage = SessionStage.CONVERSATION,
        pageId = PAGE
    )

    private fun grant() {
        link.set(floor = TalkFloor.YOU)
        link.events.tryEmit(TalkEvent.FloorGrantedToYou)
    }

    private fun capturing(): Boolean = sources.size == 1 && sources[0].started && sent.count.get() > 2

    private fun startCapture() {
        conversation()
        assertTrue(audio.press())
        grant()
        await("capturing") { capturing() }
    }

    private fun assertStopped() {
        await("capture stopped") { sources.single().closed }
        val n = sent.count.get()
        Thread.sleep(30)
        assertEquals("nothing is sent after the stop", n, sent.count.get())
    }

    @Test(timeout = 60_000)
    fun pressPrewarmsWithoutRecordingOrSendingBeforeTheGrant() {
        conversation()
        assertTrue(audio.press())
        await("prewarm") { sources.size == 1 }
        Thread.sleep(80)
        assertFalse(sources.single().started)
        assertEquals(0, sent.threads.size)
        assertEquals(1, link.presses.get())
    }

    @Test(timeout = 60_000)
    fun grantStartsCaptureOnOneThreadSendingRawPackets() {
        startCapture()
        val threads = sent.threads.toSet()
        assertEquals(1, threads.size)
        assertEquals("ptt-capture", threads.single().name)
        assertEquals(1, sources.size)
    }

    @Test(timeout = 60_000)
    fun aRefusedPressStartsNothing() {
        conversation()
        link.pressResult = false
        assertFalse(audio.press())
        // The pre-warm opened for the press is undone; nothing ever records or sends.
        await("prewarm undone") { sources.all { it.closed } }
        assertTrue(sources.none { it.started })
        assertEquals(0, sent.threads.size)
    }

    @Test(timeout = 60_000)
    fun aGrantWithoutAPressStillCapturesWhenTheFloorIsYours() {
        conversation()
        grant()
        await("capturing") { capturing() }
    }

    @Test(timeout = 60_000)
    fun aGrantEventWhenTheFloorIsNotYoursCapturesNothing() {
        conversation(TalkFloor.PEER_TALKING)
        link.events.tryEmit(TalkEvent.FloorGrantedToYou)
        Thread.sleep(50)
        assertTrue(sources.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aGrantWhileNotLiveCapturesNothing() {
        link.set(
            floor = TalkFloor.YOU,
            connection = TalkConnection.CONNECTING,
            stage = SessionStage.CONVERSATION,
            pageId = PAGE
        )
        link.events.tryEmit(TalkEvent.FloorGrantedToYou)
        Thread.sleep(50)
        assertTrue(sources.isEmpty())
    }

    @Test(timeout = 60_000)
    fun stopCaptureEventStops() {
        startCapture()
        link.events.tryEmit(TalkEvent.StopCapture)
        assertStopped()
    }

    @Test(timeout = 60_000)
    fun ownFloorReleasedStops() {
        startCapture()
        link.events.tryEmit(TalkEvent.FloorReleased(yours = true))
        assertStopped()
    }

    @Test(timeout = 60_000)
    fun sessionEndedStops() {
        startCapture()
        link.events.tryEmit(TalkEvent.SessionEnded(SessionEndReason.ENDED_BY_PEER))
        assertStopped()
        assertTrue(platform.calls.contains("restore"))
    }

    @Test(timeout = 60_000)
    fun connectionLeavingLiveStops() {
        startCapture()
        link.set(connection = TalkConnection.CONNECTING)
        assertStopped()
    }

    @Test(timeout = 60_000)
    fun floorLeavingYouStops() {
        startCapture()
        link.set(floor = TalkFloor.IDLE)
        assertStopped()
    }

    @Test(timeout = 60_000)
    fun sendOpusFalseStops() {
        startCapture()
        sent.accept = false
        assertStopped()
    }

    @Test(timeout = 60_000)
    fun releaseStopsAndTellsTheController() {
        startCapture()
        audio.release()
        assertEquals(1, link.releases.get())
        assertStopped()
    }

    @Test(timeout = 60_000)
    fun capGivesTheFloorBack() {
        useFastClock = true
        conversation()
        grant()
        await("cap reached") { link.releases.get() == 1 }
        assertEquals(2975, sent.count.get())
    }

    @Test(timeout = 60_000)
    fun busyOrNoPeerCancelsThePrewarmWithoutEverRecording() {
        conversation()
        audio.press()
        await("prewarm") { sources.size == 1 }
        link.events.tryEmit(TalkEvent.FloorBusy)
        await("closed") { sources.single().closed }
        assertFalse(sources.single().started)
        assertEquals(0, sent.threads.size)
    }

    @Test(timeout = 60_000)
    fun peerWinningTheFloorCancelsThePrewarm() {
        conversation()
        audio.press()
        await("prewarm") { sources.size == 1 }
        link.set(floor = TalkFloor.PEER_TALKING)
        await("closed") { sources.single().closed }
        assertFalse(sources.single().started)
    }

    @Test(timeout = 60_000)
    fun nothingStartsAfterClose() {
        conversation()
        audio.close()
        assertFalse(audio.press())
        grant()
        Thread.sleep(50)
        assertTrue(sources.isEmpty())
        assertTrue(platform.calls.contains("restore"))
    }

    @Test(timeout = 60_000)
    fun otherPagesStateIsIgnored() {
        link.set(
            floor = TalkFloor.YOU,
            connection = TalkConnection.LIVE,
            stage = SessionStage.CONVERSATION,
            pageId = "other"
        )
        link.events.tryEmit(TalkEvent.FloorGrantedToYou)
        Thread.sleep(50)
        assertTrue(sources.isEmpty())
        assertFalse(platform.calls.contains("enter"))
    }

    @Test(timeout = 60_000)
    fun routingIsActiveOnlyForTheConversation() {
        link.set(stage = SessionStage.WAITING, connection = TalkConnection.LIVE, pageId = PAGE)
        assertFalse(platform.calls.contains("enter"))
        link.set(stage = SessionStage.CONVERSATION)
        assertTrue(platform.calls.contains("enter"))
        link.set(stage = SessionStage.ENDED)
        assertTrue(platform.calls.contains("restore"))
    }

    @Test(timeout = 60_000)
    fun incomingFramesArePlayedAndTheFloorLeavingPeerTalkingLetsTheBufferDrain() {
        conversation(TalkFloor.PEER_TALKING)
        for (i in 0..2) link.incomingOpus.tryEmit(IncomingOpus("t1", i, byteArrayOf((i + 1).toByte())))
        await("played") { sinks.isNotEmpty() && sinks.single().writes.size == 3 }
        assertEquals(listOf(1, 2, 3), sinks.single().writes.toList())
        link.set(floor = TalkFloor.IDLE)
        link.incomingOpus.tryEmit(IncomingOpus("t1", 3, byteArrayOf(4)))
        Thread.sleep(250)
        assertEquals(3, sinks.single().writes.size)
        await("decoder freed") { decoders.single().closed }
    }

    @Test(timeout = 60_000)
    fun sessionEndFlushesPlayback() {
        conversation(TalkFloor.PEER_TALKING)
        link.incomingOpus.tryEmit(IncomingOpus("t1", 0, byteArrayOf(1)))
        link.events.tryEmit(TalkEvent.SessionEnded(SessionEndReason.ENDED_BY_PEER))
        await("decoder freed") { decoders.single().closed }
        Thread.sleep(200)
        assertTrue(sinks.isEmpty() || sinks.single().writes.isEmpty())
    }

    @Test(timeout = 60_000)
    fun captureStatusTellsWhyCaptureStopped() {
        assertEquals(CaptureStatus.IDLE, audio.captureStatus.value)
        startCapture()
        assertEquals(CaptureStatus.CAPTURING, audio.captureStatus.value)
        audio.release()
        assertEquals(CaptureStatus.IDLE, audio.captureStatus.value)
    }

    @Test(timeout = 60_000)
    fun capStatusIsStoppedAtCap() {
        useFastClock = true
        conversation()
        grant()
        await("cap") { audio.captureStatus.value == CaptureStatus.STOPPED_AT_CAP }
        assertEquals(1, link.releases.get())
    }

    @Test(timeout = 60_000)
    fun microphoneFailureIsReportedAndGivesTheFloorBack() {
        conversation()
        grant()
        await("source") { sources.isNotEmpty() }
        sources.single().failAfter = 2
        await("status") { audio.captureStatus.value == CaptureStatus.MIC_UNAVAILABLE }
        await("released") { link.releases.get() == 1 }
    }

    @Test(timeout = 60_000)
    fun senderRefusalIsReported() {
        startCapture()
        sent.accept = false
        await("status") { audio.captureStatus.value == CaptureStatus.REFUSED }
    }

    @Test(timeout = 60_000)
    fun focusLossEndsTheTransmissionSilencesPlaybackAndFocusComesBackOnTheNextPress() {
        startCapture()
        platform.focusLost!!.invoke()
        assertEquals(CaptureStatus.FOCUS_LOST, audio.captureStatus.value)
        assertEquals(1, link.releases.get())
        assertStopped()
        platform.focusHeld = false
        assertTrue(audio.press())
        assertTrue("focus requested again", platform.focusHeld)
        assertEquals(CaptureStatus.IDLE, audio.captureStatus.value)
    }

    @Test(timeout = 60_000)
    fun routingDoesNotStartWhenLiveTalkIsUnavailable() {
        link.set(stage = SessionStage.CONVERSATION, connection = TalkConnection.UNAVAILABLE, pageId = PAGE)
        assertFalse(platform.calls.contains("enter"))
    }

    @Test(timeout = 60_000)
    fun routingDoesNotRestartAfterTalkUnavailableOrAfterClose() {
        conversation()
        assertEquals(1, platform.calls.count { it == "enter" })
        link.events.tryEmit(TalkEvent.TalkUnavailable)
        assertEquals(1, platform.calls.count { it == "restore" })
        // The stage stays CONVERSATION; state updates must not bring the call mode back.
        link.set(connection = TalkConnection.CONNECTING)
        conversation()
        assertEquals(1, platform.calls.count { it == "enter" })
        audio.close()
        conversation()
        assertEquals(1, platform.calls.count { it == "enter" })
        assertEquals(1, platform.calls.count { it == "restore" })
    }

    @Test(timeout = 60_000)
    fun aSecondPressWhileTheButtonIsDownIsRefusedAndDoesNotDisturbTheCapture() {
        startCapture()
        assertFalse(audio.press())
        assertEquals(1, sources.size)
        assertFalse(sources.single().closed)
        assertEquals(1, link.presses.get())
    }

    @Test(timeout = 60_000)
    fun frameAndStateOrderingHoldsOnARealSingleThreadDispatcher() {
        val platform2 = FakePlatform()
        val link2 = FakeLink(SentLog())
        val sink = TaSink()
        val dec = CopyOnWriteArrayList<TaDecoder>()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-control") }
        val a = TalkAudio(
            pageId = PAGE,
            link = link2,
            sourceFactory = { FakeSource() },
            encoderFactory = { FakeEncoder() },
            decoderFactory = { TaDecoder().also { dec += it } },
            sinkFactory = { sink },
            platform = platform2,
            clock = System::currentTimeMillis,
            dispatcher = executor.asCoroutineDispatcher()
        )
        try {
            link2.set(
                floor = TalkFloor.PEER_TALKING,
                connection = TalkConnection.LIVE,
                stage = SessionStage.CONVERSATION,
                pageId = PAGE
            )
            for (i in 0..2) link2.incomingOpus.tryEmit(IncomingOpus("t1", i, byteArrayOf((i + 1).toByte())))
            await("played") { sink.writes.size == 3 }
            link2.set(floor = TalkFloor.IDLE)
            link2.incomingOpus.tryEmit(IncomingOpus("t1", 3, byteArrayOf(4)))
            link2.events.tryEmit(TalkEvent.SessionEnded(SessionEndReason.ENDED_BY_PEER))
            await("restored") { platform2.calls.contains("restore") }
            Thread.sleep(200)
            assertEquals(listOf(1, 2, 3), sink.writes.toList())
        } finally {
            a.close()
        }
    }

    @Test(timeout = 60_000)
    fun afterFocusLossTheRestOfThePeersTransmissionStaysSilent() {
        conversation(TalkFloor.PEER_TALKING)
        link.incomingOpus.tryEmit(IncomingOpus("t1", 0, byteArrayOf(1)))
        await("played") { sinks.isNotEmpty() && sinks.single().writes.size == 1 }
        platform.focusLost!!.invoke()
        for (i in 1..4) link.incomingOpus.tryEmit(IncomingOpus("t1", i, byteArrayOf((i + 1).toByte())))
        Thread.sleep(300)
        assertEquals(listOf(1), sinks.single().writes.toList())
    }
}
