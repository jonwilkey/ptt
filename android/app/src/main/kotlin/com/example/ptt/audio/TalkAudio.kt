package com.example.ptt.audio

import android.content.Context
import android.os.SystemClock
import com.example.ptt.talk.IncomingOpus
import com.example.ptt.talk.SessionStage
import com.example.ptt.talk.TalkConnection
import com.example.ptt.talk.TalkEvent
import com.example.ptt.talk.TalkFloor
import com.example.ptt.talk.TalkSessionController
import com.example.ptt.talk.TalkSessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why capture is (not) running, for the talk screen ("microphone unavailable" etc.). Reset to IDLE on each press. */
enum class CaptureStatus {
    IDLE,
    CAPTURING,

    /** The 59.5 s cap was reached; the floor was given back. */
    STOPPED_AT_CAP,

    /** The microphone could not be opened or stopped delivering. */
    MIC_UNAVAILABLE,

    /** The Opus encoder could not be created or failed. */
    CODEC_UNAVAILABLE,

    /** Audio focus was taken (a call, another app); the transmission was ended. */
    FOCUS_LOST,

    /** The controller refused a frame: the floor was lost or the socket went away. */
    REFUSED
}

/** What [TalkAudio] needs from the live session (the [TalkSessionController] in production, a fake in tests). */
interface TalkLink {
    val state: StateFlow<TalkSessionState>
    val events: SharedFlow<TalkEvent>
    val incomingOpus: SharedFlow<IncomingOpus>

    fun pressTalk(): Boolean

    fun releaseTalk()

    fun sendOpus(packet: ByteArray): Boolean
}

class ControllerLink(private val controller: TalkSessionController) : TalkLink {
    override val state get() = controller.state
    override val events get() = controller.events
    override val incomingOpus get() = controller.incomingOpus

    override fun pressTalk() = controller.pressTalk()

    override fun releaseTalk() = controller.releaseTalk()

    override fun sendOpus(packet: ByteArray) = controller.sendOpus(packet)
}

/**
 * The audio of one live-talk session, owned by [com.example.ptt.talk.TalkService] (created when it starts for a page,
 * [close]d when it stops, so nothing records without the service). It watches the session and runs the pieces:
 * - capture ([Recorder], one per press): pre-warmed by [press], started by the floor being granted to this phone,
 *   stopped by [release], StopCapture, FloorReleased(yours), SessionEnded, the connection leaving LIVE, the floor
 *   leaving YOU, `sendOpus` refusing, the 59.5 s cap (which also gives the floor back), or losing audio focus;
 * - playback ([Player]): fed by a collector that only enqueues; the floor leaving PEER_TALKING (or the connection
 *   leaving LIVE) lets the buffer play out, and the session ending flushes it;
 * - routing ([AudioRouting]): active from the start of the conversation until the session ends.
 *
 * 14g wires the hold-to-talk button to [press] and [release]. Both are cheap and safe from the main thread.
 *
 * All session observation runs on [dispatcher], which must be single-threaded so frames and floor changes are
 * handled in the order the controller emitted them.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class TalkAudio(
    private val pageId: String,
    private val link: TalkLink,
    private val sourceFactory: () -> PcmSource,
    private val encoderFactory: () -> OpusEncoder,
    decoderFactory: () -> OpusDecoder,
    sinkFactory: (Int) -> AudioSink,
    platform: AudioPlatform,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val player = Player(decoderFactory, sinkFactory, clock)
    private val routing = AudioRouting(platform, onFocusLost = ::focusLost)
    private val lock = Any()
    private var recorder: Recorder? = null

    @Volatile
    private var closed = false
    private var previous = TalkSessionState()
    private val capture = MutableStateFlow(CaptureStatus.IDLE)

    /** The state of the capture, with the reason when it stopped on its own. */
    val captureStatus: StateFlow<CaptureStatus> = capture.asStateFlow()

    init {
        player.start()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.incomingOpus.collect(player::enqueue) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.state.collect(::onState) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.events.collect(::onEvent) }
    }

    /**
     * The button went down: asks for the floor and, if a request was made, pre-warms the capture. False when no
     * request can be made now (the controller said so); nothing is started then.
     */
    @Suppress("ReturnCount")
    fun press(): Boolean {
        if (closed) return false
        // A press while a capture is still up means the button is already down: not a new request.
        val busy = synchronized(lock) {
            recorder?.phase.let {
                it == Recorder.Phase.PREWARM ||
                    it == Recorder.Phase.CAPTURING
            }
        }
        if (busy) return false
        capture.value = CaptureStatus.IDLE
        routing.ensureFocus()
        // Pre-warm first so the codec opens while the request is in flight; undo it if no request is made.
        swapRecorder()?.prewarm()
        if (!link.pressTalk()) {
            stopRecorder()
            return false
        }
        return true
    }

    /** The button went up (or was cancelled): ends the transmission, or a request still in flight. */
    fun release() {
        link.releaseTalk()
        stopRecorder()
    }

    /** Releases the recorder, player, routing and focus. Idempotent. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        scope.cancel()
        stopRecorder()
        player.close()
        routing.close()
        (dispatcher as? Closeable)?.close()
    }

    private fun onState(s: TalkSessionState) {
        if (closed || s.pageId != pageId) return
        val before = previous
        previous = s
        val live = s.connection == TalkConnection.LIVE
        when (s.stage) {
            // Live talk that went unavailable keeps the stage, but no audio session is wanted any more.
            SessionStage.CONVERSATION -> if (s.connection != TalkConnection.UNAVAILABLE) routing.start()

            SessionStage.ENDED -> return shutdown()

            else -> Unit
        }
        if (peerStoppedTalking(before, s)) player.release()
        if (s.floor == TalkFloor.PEER_TALKING && before.floor != TalkFloor.PEER_TALKING) routing.ensureFocus()
        if (!live || captureOver(s)) stopRecorder()
    }

    /** The peer's floor ended, or its connection dropped while it was talking: let the buffer play out. */
    private fun peerStoppedTalking(before: TalkSessionState, now: TalkSessionState): Boolean {
        val talking = now.floor == TalkFloor.PEER_TALKING
        val left = before.floor == TalkFloor.PEER_TALKING && !talking
        return left || (talking && now.connection != TalkConnection.LIVE)
    }

    /** Capture that must stop because of the floor: lost while recording, or a pre-warm the peer/busy overtook. */
    private fun captureOver(s: TalkSessionState): Boolean = when (recorder?.phase) {
        Recorder.Phase.CAPTURING -> s.floor != TalkFloor.YOU

        // A request in flight has floor IDLE and is fine; once the peer or "busy" wins, the pre-warm is useless.
        Recorder.Phase.PREWARM -> s.floor == TalkFloor.PEER_TALKING || s.floor == TalkFloor.BUSY

        else -> false
    }

    private fun onEvent(e: TalkEvent) {
        if (closed) return
        when (e) {
            TalkEvent.FloorGrantedToYou -> grantedToYou()
            is TalkEvent.FloorReleased -> if (e.yours) stopRecorder() else player.release()
            TalkEvent.StopCapture -> stopRecorder()
            TalkEvent.FloorBusy, TalkEvent.NoPeer -> stopRecorder()
            is TalkEvent.SessionEnded, TalkEvent.TalkUnavailable -> shutdown()
            TalkEvent.PeerJoined, TalkEvent.PeerLeft, TalkEvent.PeerStartedTalking -> Unit
        }
    }

    private fun grantedToYou() {
        val s = link.state.value
        if (closed || !mayCapture(s)) return
        val current = synchronized(lock) { recorder }
        when (current?.phase) {
            Recorder.Phase.PREWARM, Recorder.Phase.IDLE -> startCapture(current)

            Recorder.Phase.CAPTURING -> Unit

            // a repeated grant
            Recorder.Phase.DONE, null -> swapRecorder()?.let(::startCapture)
        }
    }

    private fun startCapture(r: Recorder) {
        capture.value = CaptureStatus.CAPTURING
        r.start(clock())
    }

    /** The floor is ours, the socket is up, and the state is this page's. */
    private fun mayCapture(s: TalkSessionState): Boolean =
        s.pageId == pageId && s.floor == TalkFloor.YOU && s.connection == TalkConnection.LIVE

    /** Replaces the recorder by a fresh one (stopping the old) and returns it, or null once closed. */
    private fun swapRecorder(): Recorder? {
        lateinit var fresh: Recorder
        fresh =
            Recorder(link::sendOpus, sourceFactory, encoderFactory, clock) { reason -> recorderStopped(fresh, reason) }
        val old = synchronized(lock) {
            if (closed) return null
            recorder.also { recorder = fresh }
        }
        old?.stop()
        return fresh
    }

    private fun stopRecorder(final: CaptureStatus = CaptureStatus.IDLE) {
        val old = synchronized(lock) { recorder.also { recorder = null } }
        if (final != CaptureStatus.IDLE || capture.value == CaptureStatus.CAPTURING) capture.value = final
        old?.stop()
    }

    /** Runs on the capture thread when a recorder ends by itself. Ignored if it is no longer the current one. */
    private fun recorderStopped(r: Recorder, reason: StopReason) {
        if (!synchronized(lock) { recorder === r }) return
        capture.value = when (reason) {
            StopReason.MAX_DURATION -> CaptureStatus.STOPPED_AT_CAP
            StopReason.MIC_UNAVAILABLE -> CaptureStatus.MIC_UNAVAILABLE
            StopReason.CODEC_UNAVAILABLE -> CaptureStatus.CODEC_UNAVAILABLE
            StopReason.SEND_REFUSED -> CaptureStatus.REFUSED
            StopReason.REQUESTED -> CaptureStatus.IDLE
        }
        when (reason) {
            // The cap or a device failure while the floor may still be ours: give it back to the server.
            StopReason.MAX_DURATION, StopReason.MIC_UNAVAILABLE, StopReason.CODEC_UNAVAILABLE -> link.releaseTalk()

            StopReason.SEND_REFUSED, StopReason.REQUESTED -> Unit
        }
    }

    /**
     * Another app or a call took audio focus: end our transmission, silence the playback and drop what is buffered.
     * Focus is asked for again at the next press or incoming transmission ([AudioRouting.ensureFocus]).
     */
    private fun focusLost() {
        if (closed) return
        val capturing = synchronized(lock) { recorder != null }
        if (capturing) {
            stopRecorder(CaptureStatus.FOCUS_LOST)
            link.releaseTalk()
        }
        player.dropCurrent()
    }

    private fun shutdown() {
        stopRecorder()
        player.flush()
        routing.close()
    }
}

/** Builds the [TalkAudio] of a session from the real platform pieces. */
class TalkAudioFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TalkSessionController
) {
    fun create(pageId: String): TalkAudio = TalkAudio(
        pageId = pageId,
        link = ControllerLink(controller),
        sourceFactory = ::AudioRecordSource,
        encoderFactory = ::MediaCodecOpusEncoder,
        decoderFactory = ::MediaCodecOpusDecoder,
        sinkFactory = ::AudioTrackSink,
        platform = AndroidAudioPlatform(context),
        clock = SystemClock::elapsedRealtime,
        dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ptt-audio-control") }.asCoroutineDispatcher()
    )
}
