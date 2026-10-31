package com.example.ptt.talk

import com.example.ptt.crypto.AudioSession
import com.example.ptt.crypto.FrameSealer
import com.example.ptt.crypto.FrameVerdict
import com.example.ptt.crypto.GrantOutcome
import com.example.ptt.crypto.ProtocolException
import com.example.ptt.data.ActivePageEntity
import com.example.ptt.data.ActivePageRepository
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PageRole
import com.example.ptt.net.ClientMessage
import com.example.ptt.net.CloseReaction
import com.example.ptt.net.DeviceTokenStore
import com.example.ptt.net.ServerMessage
import com.example.ptt.net.SessionEndReason
import com.example.ptt.net.SocketEvent
import com.example.ptt.net.TalkAuth
import com.example.ptt.net.TalkSocket
import com.example.ptt.net.TalkTransport
import com.example.ptt.net.WsErrorCode
import com.example.ptt.net.WsPageState
import com.example.ptt.outbox.Backoff
import com.example.ptt.outbox.OutboxOutcome
import com.example.ptt.push.ActivePageRules
import com.example.ptt.push.SelfPages
import com.example.ptt.push.SessionKeys
import com.example.ptt.push.SocketPageEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the live session is in its life, for the notification and the screen. [ENDED] means the page is over. When
 * live talk merely stops being available (see [TalkConnection.UNAVAILABLE]) the stage is kept: the page goes on.
 */
enum class SessionStage {
    NONE,

    /** The sender, before an answer: nobody to talk to yet. */
    WAITING,

    /** Both phones are (or will be) in the conversation. */
    CONVERSATION,
    ENDED
}

enum class TalkConnection {
    IDLE,

    /** Not (yet) joined: connecting, reconnecting, or waiting for the REST create/answer to be accepted. */
    CONNECTING,

    /** The socket is up and the join was acknowledged. */
    LIVE,

    /** No live talk: the server has none (HTTP 404), the socket was replaced, or it keeps failing. */
    UNAVAILABLE
}

enum class TalkFloor {
    IDLE,

    /** This phone holds the floor (a validated `floor_granted` for its own request). */
    YOU,

    /** The peer holds it: set only from a validated `floor_granted` (the phone checked the talker). */
    PEER_TALKING,

    /** The last request was refused and nobody is known to be talking. */
    BUSY
}

/**
 * Everything 14f (audio) and 14g (talk screen) observe. [pageProgress] is the last page state the WebSocket
 * reported (sender only; the active-page row and `PageEvent`s stay the source of truth for the screen).
 */
data class TalkSessionState(
    val pageId: String? = null,
    val stage: SessionStage = SessionStage.NONE,
    val connection: TalkConnection = TalkConnection.IDLE,
    val peerPresent: Boolean = false,
    val floor: TalkFloor = TalkFloor.IDLE,
    val rttMillis: Long? = null,
    val pageProgress: WsPageState? = null
)

/** One decrypted 20 ms Opus packet of the peer's transmission; [seq] orders it for the jitter buffer. */
class IncomingOpus(val transmissionId: String, val seq: Int, val opus: ByteArray)

/** One-shot happenings, for chirps and buzzes (14g). */
sealed interface TalkEvent {
    data object PeerJoined : TalkEvent

    data object PeerLeft : TalkEvent

    /** This phone's request was granted: capture may start (`sendOpus` now works). */
    data object FloorGrantedToYou : TalkEvent

    data object PeerStartedTalking : TalkEvent

    /** A transmission ended (either side); [yours] says whose. */
    data class FloorReleased(val yours: Boolean) : TalkEvent

    /** The request was refused: buzz. */
    data object FloorBusy : TalkEvent

    /** `floor_request` while nobody is there to hear it. */
    data object NoPeer : TalkEvent

    /** Stop capturing now: the sealer refused (past the frame limit) or the floor was lost. */
    data object StopCapture : TalkEvent

    data class SessionEnded(val reason: SessionEndReason) : TalkEvent

    data object TalkUnavailable : TalkEvent
}

/** The part of the live session the talk screen's view model needs (a seam for its tests). */
interface TalkSessions {
    /** False for a page that never has a live session (the setup Test alert). */
    fun eligible(pageId: String): Boolean

    /**
     * End was pressed: sends the WebSocket `session_end` if the socket is joined (true when queued). It only ends
     * the peer's session sooner; the caller still cancels by REST (idempotent), because a half-dead socket can lose it.
     */
    fun requestEnd(pageId: String): Boolean
}

/**
 * The live-talk session of this phone (one at a time), docs/protocol.md section 11.
 *
 * Lifecycle: [TalkService] calls [open] when it starts for a page and [stop] when it is destroyed; the controller
 * asks the service to stop ([TalkServiceControl]) when the session is over. A session
 * 1. checks the page is eligible (a row exists, not a self page, the session key and envelope peers are held),
 *    builds the one [AudioSession] with the right role from a [SessionKeys.copy];
 * 2. waits until the REST call that creates the session state was accepted (2xx): `POST /v1/pages` for the sender
 *    (seen on the outbox outcomes, or the row having moved past `sending`), `answer` for the answerer;
 * 3. opens a socket, sends `hello`, then `join`, and handles the server's messages strictly in socket order on
 *    this one coroutine;
 * 4. on any close calls [AudioSession.onSocketClosed] first, then (depending on the close) reconnects with
 *    jittered backoff, never recreating the [AudioSession], or ends.
 * It ends on `session_end`, when the active-page row says the page is over, on a refused join, and on [stop];
 * only then is the [AudioSession] closed (zeroing its key).
 *
 * API for the audio and screen code: [state], [events], [incomingOpus], [pressTalk]/[releaseTalk], [sendOpus],
 * [requestEnd]. All are safe to call from any thread.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class TalkSessionController(
    private val transport: TalkTransport,
    private val auth: TalkAuth,
    private val appVersion: String,
    private val sessionKeys: SessionKeys,
    private val activePage: ActivePageRepository,
    private val tokens: DeviceTokenStore,
    private val outcomes: Flow<OutboxOutcome>,
    private val pageEvents: SocketPageEvents,
    private val selfPages: SelfPages,
    private val service: TalkServiceControl,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val backoff: Backoff = Backoff(capMs = RECONNECT_CAP_MS)
) : TalkSessions {
    private val mutableState = MutableStateFlow(TalkSessionState())

    /** The session's observable state. */
    val state: StateFlow<TalkSessionState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<TalkEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** One-shot events (chirps, buzz, capture stop, session end). */
    val events: SharedFlow<TalkEvent> = mutableEvents.asSharedFlow()

    private val opus = MutableSharedFlow<IncomingOpus>(
        extraBufferCapacity = OPUS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Decrypted Opus packets of the peer's transmission with their transmission id and seq: only frames the
     * [AudioSession] accepted. Packets can be missing (the server drops under load) or out of order.
     */
    val incomingOpus: SharedFlow<IncomingOpus> = opus.asSharedFlow()

    /** Pages whose live talk ended in a way the page survives (unavailable, replaced, unauthorized, no key). */
    private val noTalkPages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Pages whose create/answer REST call the server accepted (2xx), whether or not a session was open then. */
    private val accepted = MutableStateFlow<Set<String>>(emptySet())
    private val lock = Any()

    @Volatile
    private var run: Run? = null

    /** Test hook: how many [AudioSession]s were built (a reconnect must not build another). */
    @Volatile
    internal var audioSessionsBuilt = 0
        private set

    /** Subscribes to the outbox outcomes (they do not replay); call once at app start. */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            outcomes.filter { it.kind == OutboxKind.PAGE || it.kind == OutboxKind.ANSWER }.collect(::outcome)
        }
    }

    private fun outcome(o: OutboxOutcome) {
        when (val result = o.result) {
            is OutboxOutcome.Result.Sent -> if (o.kind == OutboxKind.PAGE && result.pageState == STATE_ENDED) {
                run?.takeIf { it.pageId == o.pageId }?.localEnd()
            } else {
                accepted.update { (it + o.pageId).toList().takeLast(ACCEPTED_MEMORY).toSet() }
            }

            is OutboxOutcome.Result.Rejected, OutboxOutcome.Result.Expired ->
                run?.takeIf { it.pageId == o.pageId }?.localEnd()

            OutboxOutcome.Result.CancelledLocally -> run?.takeIf { it.pageId == o.pageId }?.localEnd()
        }
    }

    /** False for a page that never has a live session (the setup Test alert goes to this phone itself). */
    override fun eligible(pageId: String): Boolean = !selfPages.contains(pageId)

    /** Starts the session of [pageId] (idempotent); a different earlier session is closed first. */
    fun open(pageId: String) {
        if (pageId in noTalkPages) {
            // Live talk already failed for this page for good; the page itself goes on.
            service.stop(pageId)
            return
        }
        synchronized(lock) {
            val current = run
            if (current != null && current.pageId == pageId && current.job?.isActive == true) return
            current?.stopNow()
            val next = Run(pageId)
            run = next
            next.job = scope.launch(start = CoroutineStart.LAZY) { next.execute() }
            next.job?.start()
        }
    }

    /** The service is going away: ends [pageId]'s session without telling the server anything. */
    fun stop(pageId: String) {
        synchronized(lock) { run?.takeIf { it.pageId == pageId }?.stopNow() }
    }

    /** The push-to-talk button went down. False if a request cannot be made now (not joined, already pressed). */
    fun pressTalk(): Boolean = run?.press() ?: false

    /** The button went up: ends the own transmission, or makes a late grant be answered with a release. */
    fun releaseTalk() {
        run?.release()
    }

    /**
     * Seals one 20 ms Opus packet under the transmission that was granted and sends it as a binary frame. False
     * (and nothing sent) when this phone does not hold the floor. A sealer that refuses stops the transmission
     * ([TalkEvent.StopCapture]); the caller never reseals or builds its own sealer.
     */
    fun sendOpus(packet: ByteArray): Boolean = run?.sendOpus(packet) ?: false

    /**
     * End was pressed. When the socket is joined, sends the WebSocket `session_end` (section 11.5) as a courtesy
     * that ends the peer's session at once, and returns whether it was queued. The caller ALWAYS also cancels by
     * REST (both are idempotent; a 410 is harmless): a half-dead socket can lose the message.
     */
    override fun requestEnd(pageId: String): Boolean = run?.takeIf { it.pageId == pageId }?.sendEnd() ?: false

    private inner class Run(val pageId: String) {
        @Volatile
        var job: Job? = null

        private val endedFlow = MutableStateFlow(false)

        /** Set once the session is winding down; waits on it (see [awaitAccepted]) are woken by the flow. */
        private var ended: Boolean
            get() = endedFlow.value
            set(value) {
                endedFlow.value = value
            }

        /** Live talk is over but the page goes on (unavailable, replaced, unauthorized, no key). */
        @Volatile
        private var pageContinues = false

        private val audioLock = Any()

        @Volatile
        private var socket: TalkSocket? = null

        @Volatile
        private var sealer: FrameSealer? = null

        @Volatile
        private var joined = false

        @Volatile
        private var pressed = false

        @Volatile
        private var outstanding: String? = null

        private var audio: AudioSession? = null
        private var role = PageRole.SENDER
        private var sessionId = ""
        private var peerDevice: String? = null
        private var floorId: String? = null
        private var joinFailures = 0
        private var protocolErrors = 0
        private var joinRetry: Job? = null
        private val wake = Channel<Unit>(Channel.CONFLATED)

        suspend fun execute() {
            var byStopNow = false
            try {
                val row = activePage.current()?.takeIf { it.pageId == pageId }
                if (row == null || !eligible(pageId)) return
                mutableState.value = TalkSessionState(
                    pageId = pageId,
                    stage = stageOf(row),
                    connection = TalkConnection.CONNECTING
                )
                if (!prepare(row)) {
                    unavailable()
                    return
                }
                val watcher = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    activePage.active.collect(::rowChanged)
                }
                try {
                    awaitAccepted(row)
                    if (!ended) connectLoop()
                } finally {
                    watcher.cancel()
                }
            } catch (e: CancellationException) {
                byStopNow = true
                throw e
            } finally {
                cleanup(byStopNow)
            }
        }

        private fun stageOf(row: ActivePageEntity) = if (row.role == PageRole.RECEIVER || row.state == STATE_ANSWERED) {
            SessionStage.CONVERSATION
        } else {
            SessionStage.WAITING
        }

        @Suppress("ReturnCount")
        private fun prepare(row: ActivePageEntity): Boolean {
            val own = tokens.deviceId() ?: return false
            val peers = sessionKeys.peers(pageId)
            val key = sessionKeys.copy(pageId) ?: return false
            return try {
                audioSessionsBuilt++
                role = row.role
                sessionId = row.sessionId
                audio = if (row.role == PageRole.RECEIVER) {
                    val from = peers.singleOrNull() ?: return false
                    if (from == own) return false
                    AudioSession.forAnswerer(key, row.sessionId, own, from)
                } else {
                    val others = peers - own
                    if (others.isEmpty()) return false
                    AudioSession.forSender(key, row.sessionId, own, others)
                }
                true
            } catch (_: ProtocolException) {
                false
            } finally {
                key.fill(0)
            }
        }

        /**
         * No usable key or envelope (for example after a process death): live talk is unavailable for this page.
         * The page itself is left alone: paging, End and the REST paths work without a session key.
         */
        private fun unavailable() {
            pageContinues = true
            noTalkPages.add(pageId)
            mutableState.update { it.copy(connection = TalkConnection.UNAVAILABLE) }
            mutableEvents.tryEmit(TalkEvent.TalkUnavailable)
        }

        private suspend fun awaitAccepted(row: ActivePageEntity) {
            if (row.role == PageRole.RECEIVER) {
                combine(accepted, endedFlow) { set, over -> pageId in set || over }.first { it }
            } else {
                combine(accepted, activePage.active, endedFlow) { set, current, over ->
                    val state = current?.takeIf { it.pageId == pageId }?.state
                    pageId in set || over || (state != null && state != ActivePageRules.STATE_SENDING)
                }.first { it }
            }
        }

        private fun rowChanged(row: ActivePageEntity?) {
            val mine = row?.takeIf { it.pageId == pageId }
            if (mine == null || mine.state in OVER_STATES) {
                localEnd()
                return
            }
            if (role == PageRole.SENDER && mine.state == STATE_ANSWERED) conversation()
        }

        private fun conversation() = mutableState.update {
            if (it.stage == SessionStage.WAITING) it.copy(stage = SessionStage.CONVERSATION) else it
        }

        /** The page is over for reasons found locally (no server message): wind down. */
        fun localEnd() {
            ended = true
            wake.trySend(Unit)
            socket?.close()
        }

        fun stopNow() {
            ended = true
            wake.trySend(Unit)
            job?.cancel()
        }

        @Suppress("ReturnCount")
        private suspend fun connectLoop() {
            var attempt = 0
            while (!ended) {
                val s = TalkSocket(transport, auth, appVersion, scope, nowMs)
                socket = s
                joined = false
                joinFailures = 0
                s.open()
                val pumped = pump(s)
                joinRetry?.cancel()
                socket = null
                if (ended) return
                val closed = pumped.closed
                if (!applyClose(closed?.reaction ?: CloseReaction.RECONNECT, closed)) return
                if (pumped.readySince >= 0 && nowMs() - pumped.readySince >= STABLE_MS) attempt = 0
                attempt++
                withTimeoutOrNull(backoff.delayMs(attempt)) { wake.receive() }
            }
        }

        private inner class Pumped(val closed: SocketEvent.Closed?, val readySince: Long)

        /** Handles one socket's events, in order, until it is closed. */
        private suspend fun pump(s: TalkSocket): Pumped {
            var readySince = -1L
            var closed: SocketEvent.Closed? = null
            for (event in s.events) {
                when (event) {
                    is SocketEvent.Ready -> {
                        readySince = nowMs()
                        s.send(ClientMessage.Join(sessionId))
                    }

                    is SocketEvent.Message -> if (!ended) handle(s, event.message)

                    is SocketEvent.Frame -> if (!ended) onFrame(event.bytes)

                    is SocketEvent.Rtt -> mutableState.update { it.copy(rttMillis = event.millis) }

                    is SocketEvent.Closed -> {
                        socketLost()
                        closed = event
                    }
                }
            }
            return Pumped(closed, readySince)
        }

        /** Applies the close; false if the session must not reconnect. */
        private fun applyClose(reaction: CloseReaction, closed: SocketEvent.Closed?): Boolean {
            val protocolFault = closed?.code == CODE_PROTOCOL || closed?.code == CODE_TOO_LARGE
            protocolErrors = if (protocolFault) protocolErrors + 1 else 0
            val unavailable = reaction == CloseReaction.UNAVAILABLE || protocolErrors >= MAX_PROTOCOL_ERRORS
            return when {
                reaction == CloseReaction.UNAUTHORIZED || reaction == CloseReaction.REPLACED -> {
                    // 4401/4403 are handled like a REST 401 (TalkAuth flagged re-enrollment); 4409: do not reconnect.
                    stopTalking(reaction == CloseReaction.REPLACED)
                    false
                }

                unavailable -> {
                    stopTalking(true)
                    false
                }

                else -> true
            }
        }

        private fun stopTalking(unavailable: Boolean) {
            ended = true
            pageContinues = true
            noTalkPages.add(pageId)
            mutableState.update { it.copy(connection = TalkConnection.UNAVAILABLE) }
            if (unavailable) mutableEvents.tryEmit(TalkEvent.TalkUnavailable)
        }

        /** The socket closed: before anything else the audio state is told (never recreated on reconnect). */
        private fun socketLost() {
            var wasTalking = false
            synchronized(audioLock) {
                audio?.onSocketClosed()
                wasTalking = sealer != null || mutableState.value.floor == TalkFloor.YOU
                sealer = null
                joined = false
                pressed = false
                outstanding = null
                floorId = null
            }
            mutableState.update {
                it.copy(
                    connection = if (ended) it.connection else TalkConnection.CONNECTING,
                    peerPresent = false,
                    floor = TalkFloor.IDLE
                )
            }
            if (wasTalking) {
                // The own transmission is lost with the socket: capture must stop (a new request needs a new id).
                mutableEvents.tryEmit(TalkEvent.StopCapture)
                mutableEvents.tryEmit(TalkEvent.FloorReleased(yours = true))
            }
        }

        // --- server messages, in socket order ---

        @Suppress("CyclomaticComplexMethod")
        private suspend fun handle(s: TalkSocket, m: ServerMessage) {
            when (m) {
                is ServerMessage.PageState -> pageState(m)
                is ServerMessage.PeerJoined -> peerJoined(m.deviceId)
                is ServerMessage.PeerLeft -> peerLeft(m.deviceId)
                is ServerMessage.FloorGranted -> granted(s, m)
                ServerMessage.FloorBusy -> busy()
                is ServerMessage.FloorReleased -> released(m)
                is ServerMessage.SessionEnd -> endSession(m.reason)
                is ServerMessage.Error -> error(s, m.code)
                is ServerMessage.Hello, is ServerMessage.Pong -> Unit
            }
        }

        private suspend fun pageState(m: ServerMessage.PageState) {
            if (m.pageId != pageId) return
            if (!joined) {
                joined = true
                joinRetry?.cancel()
                mutableState.update { it.copy(connection = TalkConnection.LIVE) }
            }
            if (role != PageRole.SENDER) return
            mutableState.update { it.copy(pageProgress = it.pageProgress.forward(m.state)) }
            pageEvents.socketPageState(pageId, m.state)
            if (m.state == WsPageState.ANSWERED) conversation()
        }

        /** Page progress only moves forward, in the order of [ActivePageRules.senderRank] (not the enum's). */
        private fun WsPageState?.forward(next: WsPageState): WsPageState? {
            val rank = ActivePageRules.senderRank(next.wire)
            val current = this?.let { ActivePageRules.senderRank(it.wire) } ?: -1
            return if (rank > current) next else this
        }

        private fun peerJoined(device: String) {
            if (audio?.onPeerJoined(device) != true) return
            peerDevice = device
            mutableState.update { it.copy(peerPresent = true) }
            mutableEvents.tryEmit(TalkEvent.PeerJoined)
        }

        private fun peerLeft(device: String) {
            if (device != peerDevice) return
            mutableState.update { it.copy(peerPresent = false) }
            mutableEvents.tryEmit(TalkEvent.PeerLeft)
        }

        private fun granted(s: TalkSocket, m: ServerMessage.FloorGranted) = synchronized(audioLock) {
            when (val outcome = audio?.onFloorGranted(m.talker, m.transmissionId) ?: GrantOutcome.Ignored) {
                is GrantOutcome.Transmit -> {
                    if (outstanding == m.transmissionId) outstanding = null
                    sealer = outcome.sealer
                    floorId = m.transmissionId
                    mutableState.update { it.copy(floor = TalkFloor.YOU) }
                    mutableEvents.tryEmit(TalkEvent.FloorGrantedToYou)
                }

                is GrantOutcome.SendRelease -> {
                    if (outstanding == outcome.transmissionId) outstanding = null
                    s.send(ClientMessage.FloorRelease(outcome.transmissionId))
                }

                GrantOutcome.Listen -> {
                    floorId = m.transmissionId
                    mutableState.update { it.copy(floor = TalkFloor.PEER_TALKING) }
                    mutableEvents.tryEmit(TalkEvent.PeerStartedTalking)
                }

                GrantOutcome.Ignored -> Unit
            }
        }

        private fun busy() {
            synchronized(audioLock) {
                failOutstanding()
                // With a validated talker on record the floor state already says who; otherwise it is just busy.
                mutableState.update { if (it.floor == TalkFloor.IDLE) it.copy(floor = TalkFloor.BUSY) else it }
            }
            mutableEvents.tryEmit(TalkEvent.FloorBusy)
        }

        private fun failOutstanding() {
            outstanding?.let { audio?.onRequestFailed(it) }
            outstanding = null
        }

        private fun released(m: ServerMessage.FloorReleased) {
            var yours = false
            synchronized(audioLock) {
                audio?.onFloorReleased(m.transmissionId)
                if (outstanding == m.transmissionId) outstanding = null
                if (m.transmissionId != floorId) return
                yours = mutableState.value.floor == TalkFloor.YOU
                floorId = null
                sealer = null
                mutableState.update { it.copy(floor = TalkFloor.IDLE) }
            }
            mutableEvents.tryEmit(TalkEvent.FloorReleased(yours))
        }

        private suspend fun error(s: TalkSocket, code: WsErrorCode) {
            when (code) {
                WsErrorCode.NOT_FOUND -> if (!joined) joinRefused(s)

                WsErrorCode.NO_PEER -> {
                    synchronized(audioLock) { failOutstanding() }
                    mutableEvents.tryEmit(TalkEvent.NoPeer)
                }

                WsErrorCode.BAD_TRANSMISSION_ID, WsErrorCode.UNKNOWN -> synchronized(audioLock) { failOutstanding() }
            }
        }

        /** `not_found` after a 2xx means the page is over; one short retry on the same socket covers a lag. */
        private suspend fun joinRefused(s: TalkSocket) {
            joinFailures++
            if (joinFailures > MAX_JOIN_RETRIES) {
                endSession(SessionEndReason.ENDED_BY_PEER)
                return
            }
            joinRetry?.cancel()
            joinRetry = scope.launch {
                delay(JOIN_RETRY_MS)
                s.send(ClientMessage.Join(sessionId))
            }
        }

        private suspend fun endSession(reason: SessionEndReason) {
            if (ended && mutableState.value.stage == SessionStage.ENDED) return
            ended = true
            // No more sealing from here on, whatever the database write below takes.
            synchronized(audioLock) { sealer = null }
            mutableState.update { it.copy(stage = SessionStage.ENDED, floor = TalkFloor.IDLE, peerPresent = false) }
            sessionKeys.clear(pageId)
            withContext(NonCancellable) { pageEvents.sessionEnded(pageId, role, reason) }
            mutableEvents.tryEmit(TalkEvent.SessionEnded(reason))
            socket?.close()
        }

        private fun onFrame(bytes: ByteArray) {
            val verdict = audio?.onFrame(bytes) ?: return
            // Only authenticated, first-time frames are played; everything else is dropped silently.
            if (verdict is FrameVerdict.Accepted) {
                opus.tryEmit(
                    IncomingOpus(verdict.transmissionId, verdict.seq, verdict.opus)
                )
            }
        }

        // --- the talk button ---

        @Suppress("ReturnCount", "ComplexCondition")
        fun press(): Boolean = synchronized(audioLock) {
            val s = socket
            val a = audio
            if (ended || !joined || s == null || a == null || pressed) return false
            val id = try {
                a.requestFloor()
            } catch (_: ProtocolException) {
                return false
            }
            pressed = true
            outstanding = id
            if (!s.send(ClientMessage.FloorRequest(id))) {
                a.onRequestFailed(id)
                outstanding = null
                pressed = false
                return false
            }
            mutableState.update { if (it.floor == TalkFloor.BUSY) it.copy(floor = TalkFloor.IDLE) else it }
            true
        }

        fun release() = synchronized(audioLock) {
            pressed = false
            val id = audio?.buttonReleased()
            sealer = null
            if (id != null) {
                socket?.send(ClientMessage.FloorRelease(id))
                mutableState.update { if (it.floor == TalkFloor.YOU) it.copy(floor = TalkFloor.IDLE) else it }
                if (floorId == id) floorId = null
            }
        }

        @Suppress("ReturnCount")
        fun sendOpus(packet: ByteArray): Boolean = synchronized(audioLock) {
            val sealing = sealer ?: return false
            val s = socket ?: return false
            val frame = try {
                sealing.seal(packet)
            } catch (_: ProtocolException) {
                release()
                mutableEvents.tryEmit(TalkEvent.StopCapture)
                return false
            }
            s.sendFrame(frame)
        }

        // --- End ---

        /** Queues the WebSocket `session_end` if the socket is joined; the REST cancel is the caller's. */
        fun sendEnd(): Boolean {
            val s = socket
            return !ended && joined && s != null && s.send(ClientMessage.SessionEnd)
        }

        private fun cleanup(byStopNow: Boolean) {
            ended = true
            joinRetry?.cancel()
            sealer = null
            audio?.close()
            socket?.close()
            socket = null
            mutableState.update {
                when {
                    it.pageId != pageId && it.pageId != null -> it

                    // Live talk is over but the page goes on: keep the stage, say "unavailable".
                    pageContinues -> it.copy(floor = TalkFloor.IDLE, peerPresent = false)

                    else -> it.copy(stage = SessionStage.ENDED, floor = TalkFloor.IDLE, peerPresent = false)
                }
            }
            synchronized(lock) { if (run === this) run = null }
            if (!byStopNow) service.stop(pageId)
        }
    }

    companion object {
        const val RECONNECT_CAP_MS = 30_000L

        /** A socket that stayed up this long resets the reconnect backoff. */
        const val STABLE_MS = 10_000L
        const val JOIN_RETRY_MS = 1_000L
        const val MAX_JOIN_RETRIES = 2
        private const val MAX_PROTOCOL_ERRORS = 3
        private const val CODE_PROTOCOL = 4400
        private const val CODE_TOO_LARGE = 4413
        private const val EVENT_BUFFER = 32
        private const val OPUS_BUFFER = 64
        private const val ACCEPTED_MEMORY = 16
        private const val STATE_ENDED = "ended"
        private const val STATE_ANSWERED = "answered"
        private val OVER_STATES = setOf("ended", "failed", "missed")
    }
}
