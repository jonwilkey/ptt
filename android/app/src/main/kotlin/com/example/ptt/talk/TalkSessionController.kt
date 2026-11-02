package com.example.ptt.talk

import com.example.ptt.crypto.AudioSession
import com.example.ptt.crypto.ClipVerdict
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
import com.example.ptt.net.ReleaseReason
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
import java.util.BitSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/** What losing the socket did to the own transmission. */
private enum class OwnLoss {
    NONE,

    /** A press that had not been granted ended (its pre-warm must stop). */
    PREWARM,

    /** A granted protocol-1 transmission was lost. */
    TRANSMISSION
}

/** A line about this phone's own transmission, for the talk screen (docs/protocol.md section 13.6). */
enum class OwnNotice {
    NONE,

    /** `listener_gap`: the peer did not hear it live; it plays when they are back. */
    NOT_HEARD_LIVE,

    /** The floor is gone (or the socket dropped) but the transmission is still being captured or sent. */
    SENDING_AS_CLIP,

    /** The server no longer held it (`bad_transmission_id` after `resume`), and it may lack some of its frames. */
    NOT_SENT
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
    val pageProgress: WsPageState? = null,
    /**
     * The sender may not talk yet: the receiver speaks first (see [FirstTalkerRule]). A fact about the session,
     * kept here so that recreating the screen cannot lose it. Never true for the receiver.
     */
    val firstTalkerLocked: Boolean = false,
    /** The socket's `hello` reply was protocol 2 (section 13.1): talk works while the peer is away (section 13.7). */
    val protocol2: Boolean = false,
    /** This phone is capturing into its own transmission (it may outlive the floor and the socket on protocol 2). */
    val capturing: Boolean = false,
    /** An own transmission is still being captured or sent: nothing plays meanwhile (section 13.8). */
    val sending: Boolean = false,
    val ownNotice: OwnNotice = OwnNotice.NONE
)

/** What the player learns from the session, in socket order ([TalkSessionController.incoming]). */
sealed interface Incoming

/** One decrypted 20 ms Opus packet of the peer's (live or clip) transmission; [seq] orders it. */
class IncomingOpus(val transmissionId: String, val seq: Int, val opus: ByteArray) : Incoming

/** The peer's live transmission ended: its `floor_released`, or a `clip` that ended it (section 13.8). */
data class LiveEnded(val transmissionId: String) : Incoming

/**
 * An accepted `clip` (protocol 2, section 13.8): the frames of [transmissionId] up to [lastSeq] that follow are a
 * replay, played after what is already waiting. [acceptedBefore] holds the seqs accepted before it.
 */
class ClipStarted(val transmissionId: String, val lastSeq: Int, val acceptedBefore: BitSet) : Incoming

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

    /** Stop capturing now: the sealer refused, the frame limit was reached, or the transmission is over. */
    data object StopCapture : TalkEvent

    data class SessionEnded(val reason: SessionEndReason) : TalkEvent

    data object TalkUnavailable : TalkEvent
}

/** The part of the live session the talk screen's view model needs (a seam for its tests). */
interface TalkSessions {
    /** False for a page that never has a live session (the setup Test alert). */
    fun eligible(pageId: String): Boolean

    /** The session's observable state; it may belong to another page than the one shown (check its `pageId`). */
    val state: StateFlow<TalkSessionState>

    /** One-shot events (chirps, buzz, session end); lost while nobody collects, so durable UI uses [state]. */
    val events: SharedFlow<TalkEvent>

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
 * API for the audio and screen code: [state], [events], [incoming], [pressTalk]/[releaseTalk], [sendOpus],
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
    private val recovery: SenderKeyRecovery,
    private val service: TalkServiceControl,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val backoff: Backoff = Backoff(capMs = RECONNECT_CAP_MS)
) : TalkSessions {
    private val mutableState = MutableStateFlow(TalkSessionState())

    /** The session's observable state. */
    override val state: StateFlow<TalkSessionState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<TalkEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** One-shot events (chirps, buzz, capture stop, session end). */
    override val events: SharedFlow<TalkEvent> = mutableEvents.asSharedFlow()

    private val playback = MutableSharedFlow<Incoming>(extraBufferCapacity = Channel.UNLIMITED)

    /**
     * For the player, in socket order: decrypted Opus packets of the peer's transmissions (only frames the
     * [AudioSession] accepted; packets can be missing or out of order), the end of the peer's live transmissions,
     * and accepted clips (protocol 2). Nothing is dropped: a replay arrives faster than real time and its frames
     * were already reported `heard` (section 13.5); the server bounds what a replay holds (section 13.10).
     */
    val incoming: SharedFlow<Incoming> = playback.asSharedFlow()

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

    /**
     * The push-to-talk button went down. False if a request cannot be made now (not joined, already pressed). While
     * the previous own transmission is still being sent the request waits for it (section 13.6) and this is true.
     */
    fun pressTalk(): Boolean = run?.press() ?: false

    /** The button went up: ends the own transmission, or makes a late grant be answered with a release. */
    fun releaseTalk() {
        run?.release()
    }

    /**
     * Seals one 20 ms Opus packet into the own transmission's buffer and sends it as a binary frame as soon as the
     * pacing allows (section 13.6). False (and nothing kept) when this phone is not capturing a granted transmission;
     * on protocol 1 also when the frame could not go out. A sealer that refuses, or the 3000th frame, stops the
     * capture ([TalkEvent.StopCapture]); the caller never reseals or builds its own sealer.
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
        private var joined = false

        /** The current socket's `hello` reply was protocol 2 or higher (section 13.1). */
        @Volatile
        private var protocol2 = false

        @Volatile
        private var pressed = false

        /** The own transmission (section 13.6): at most one, from its `floor_request` until closed or given up. */
        private var own: TalkerTransmission? = null

        /** A press waits for the previous own transmission to be sent and closed (section 13.6). */
        private var pendingPress = false
        private var resumeSent = false

        /** The last [OwnNotice] about a finished transmission; cleared by the next press. */
        private var lastNotice = OwnNotice.NONE
        private val pacer = SendPacer()
        private var drainJob: Job? = null

        private var audio: AudioSession? = null
        private var firstTalker: FirstTalkerRule? = null
        private var lockTimer: Job? = null
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
                firstTalker = if (row.role == PageRole.SENDER) FirstTalkerRule() else null
                mutableState.value = TalkSessionState(
                    pageId = pageId,
                    stage = stageOf(row),
                    connection = TalkConnection.CONNECTING,
                    firstTalkerLocked = firstTalker != null
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
                withContext(NonCancellable) { clearKeysOfAnOverPage() }
                cleanup(byStopNow)
            }
        }

        /** Recovery may have put keys back after the page was ended; whatever exit the Run takes, they go. */
        private suspend fun clearKeysOfAnOverPage() {
            val row = activePage.current()?.takeIf { it.pageId == pageId }
            if (row == null || row.state in OVER_STATES) sessionKeys.clear(pageId)
        }

        private fun stageOf(row: ActivePageEntity) = if (row.role == PageRole.RECEIVER || row.state == STATE_ANSWERED) {
            SessionStage.CONVERSATION
        } else {
            SessionStage.WAITING
        }

        @Suppress("ReturnCount")
        private suspend fun prepare(row: ActivePageEntity): Boolean {
            val own = tokens.deviceId() ?: return false
            var session = row.sessionId
            if (row.role == PageRole.SENDER && !sessionKeys.has(pageId)) {
                // The process died since the page was sent: ask the server for the sender's own copy.
                session = recovery.recover(pageId) ?: return false
            }
            val peers = sessionKeys.peers(pageId)
            val key = sessionKeys.copy(pageId) ?: return false
            return try {
                audioSessionsBuilt++
                role = row.role
                sessionId = session
                audio = if (row.role == PageRole.RECEIVER) {
                    val from = peers.singleOrNull() ?: return false
                    if (from == own) return false
                    AudioSession.forAnswerer(key, session, own, from)
                } else {
                    val others = peers - own
                    if (others.isEmpty()) return false
                    AudioSession.forSender(key, session, own, others)
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

        /**
         * Applies [block] to the first-talker rule (senders only) and publishes the result in the state, with a timer
         * that publishes it again when the 10 s of silence run out. Called from the socket's handlers and the timer;
         * the rule's monitor orders them. A Run that is no longer the current one publishes nothing.
         */
        private fun withRule(block: (FirstTalkerRule, Long) -> Unit = { _, _ -> }) {
            val rule = firstTalker ?: return
            synchronized(rule) {
                val now = nowMs()
                block(rule, now)
                val locked = rule.isLocked(now)
                mutableState.update {
                    if (run === this && it.pageId == pageId) it.copy(firstTalkerLocked = locked) else it
                }
                lockTimer?.cancel()
                val due = rule.deadline()
                lockTimer = if (locked && due != null && !ended) {
                    scope.launch {
                        delay(due - now)
                        withRule()
                    }
                } else {
                    null
                }
            }
        }

        /** The peer's transmission was released or the session ended: for the rule their first transmission is over. */
        private fun peerStoppedTalking() = withRule { rule, _ -> rule.onPeerStoppedTalking() }

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
                        protocol2 = event.protocol >= PROTOCOL_CLIP
                        mutableState.update { it.copy(protocol2 = protocol2) }
                        if (!protocol2) synchronized(audioLock) { own?.let(::giveUp) }
                        readySince = nowMs()
                        sendJoin(s)
                    }

                    is SocketEvent.Message -> if (!ended) handle(s, event.message)

                    is SocketEvent.Frame -> if (!ended) onFrame(s, event.bytes)

                    is SocketEvent.Rtt -> {
                        mutableState.update { it.copy(rttMillis = event.millis) }
                        // Any pong after the join's page_state answers a ping sent after the join, so the whole
                        // snapshot is in (the server handles a socket's messages in order, section 11.8).
                        if (joined) withRule { rule, _ -> rule.onRejoinSettled() }
                    }

                    is SocketEvent.Closed -> {
                        socketLost()
                        closed = event
                    }
                }
            }
            return Pumped(closed, readySince)
        }

        /** After every successful join of its own page, on a protocol-2 socket (section 13.7); no reply. */
        private fun sendSenderKey(s: TalkSocket) {
            if (role != PageRole.SENDER || !protocol2) return
            sessionKeys.senderKey(pageId)?.let { s.send(ClientMessage.SenderKey(it)) }
        }

        /** `join`, then a `ping` whose `pong` marks the end of the join snapshot. */
        private fun sendJoin(s: TalkSocket) {
            s.send(ClientMessage.Join(sessionId))
            s.send(ClientMessage.Ping(nowMs()))
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

        /**
         * The socket closed: before anything else the audio state is told (never recreated on reconnect). A protocol-2
         * own transmission waits for the `resume` (section 13.6), still capturing if it was; a protocol-1 one is lost.
         */
        private fun socketLost() {
            val loss = synchronized(audioLock) {
                joined = false
                floorId = null
                resumeSent = false
                ownSocketLost().also { publishOwn() }
            }
            mutableState.update {
                it.copy(
                    connection = if (ended) it.connection else TalkConnection.CONNECTING,
                    peerPresent = false,
                    floor = TalkFloor.IDLE
                )
            }
            // The peer's transmission is suspended: the rejoin snapshot either resumes it or shows it is over.
            withRule { rule, _ -> rule.onOwnSocketLost() }
            if (loss != OwnLoss.NONE) mutableEvents.tryEmit(TalkEvent.StopCapture)
            // A protocol-1 transmission is lost with the socket: a new request needs a new id.
            if (loss == OwnLoss.TRANSMISSION) mutableEvents.tryEmit(TalkEvent.FloorReleased(yours = true))
        }

        /** What the socket's loss did to the own transmission. Under [audioLock]. */
        private fun ownSocketLost(): OwnLoss {
            val t = own
            if (t != null && t.protocol2) {
                audio?.onSocketClosed(keepOwn = t.granted)
                t.onSocketLost()
                if (t.granted) return OwnLoss.NONE
            } else {
                audio?.onSocketClosed()
                t?.let(::freeOwn)
            }
            val wasPressed = pressed
            pressed = false
            pendingPress = false
            return when {
                t?.granted == true -> OwnLoss.TRANSMISSION
                wasPressed -> OwnLoss.PREWARM
                else -> OwnLoss.NONE
            }
        }

        // --- server messages, in socket order ---

        @Suppress("CyclomaticComplexMethod")
        private suspend fun handle(s: TalkSocket, m: ServerMessage) {
            when (m) {
                is ServerMessage.PageState -> pageState(s, m)
                is ServerMessage.PeerJoined -> peerJoined(m.deviceId)
                is ServerMessage.PeerLeft -> peerLeft(m.deviceId)
                is ServerMessage.FloorGranted -> granted(s, m)
                ServerMessage.FloorBusy -> busy()
                is ServerMessage.FloorReleased -> released(s, m)
                is ServerMessage.SessionEnd -> endSession(m.reason)
                is ServerMessage.Error -> error(s, m.code)
                is ServerMessage.Clip -> if (protocol2) clip(s, m)
                is ServerMessage.ListenerGap -> if (protocol2) listenerGap(m.transmissionId)
                is ServerMessage.ResumeFrom -> if (protocol2) resumeFrom(s, m.lastSeq)
                is ServerMessage.Hello, is ServerMessage.Pong -> Unit
            }
        }

        private suspend fun pageState(s: TalkSocket, m: ServerMessage.PageState) {
            if (m.pageId != pageId) return
            if (!joined) {
                joined = true
                s.joined()
                joinRetry?.cancel()
                mutableState.update { it.copy(connection = TalkConnection.LIVE) }
                sendSenderKey(s)
                synchronized(audioLock) { afterJoin(s) }
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
            // Also the join snapshot's: a peer already there when this phone joined, or replayed after a reconnect
            // (which cannot restart the window).
            withRule { rule, now -> rule.onPeerPresent(now) }
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
                    val t = own?.takeIf { it.id == m.transmissionId && !it.granted }
                    if (t == null) {
                        // Cannot happen: the floor only transmits for the request this phone holds.
                        audio?.onOwnClosed(m.transmissionId)
                        s.send(ClientMessage.FloorRelease(m.transmissionId))
                        return@synchronized
                    }
                    t.onGranted(outcome.sealer)
                    floorId = m.transmissionId
                    mutableState.update { it.copy(floor = TalkFloor.YOU) }
                    publishOwn()
                    mutableEvents.tryEmit(TalkEvent.FloorGrantedToYou)
                }

                is GrantOutcome.SendRelease -> {
                    s.send(ClientMessage.FloorRelease(outcome.transmissionId))
                    own?.takeIf { it.id == outcome.transmissionId && !it.granted }?.let(::freeOwn)
                    // Section 12.4 rule 4 refused the grant of a request the user still holds: that is "busy".
                    if (outcome.refused) refusedBusy()
                }

                GrantOutcome.Listen -> {
                    floorId = m.transmissionId
                    mutableState.update { it.copy(floor = TalkFloor.PEER_TALKING) }
                    withRule { rule, now -> rule.onPeerStartedTalking(now, m.transmissionId) }
                    mutableEvents.tryEmit(TalkEvent.PeerStartedTalking)
                }

                GrantOutcome.Ignored -> Unit
            }
        }

        private fun busy() {
            synchronized(audioLock) { failOutstanding() }
            refusedBusy()
        }

        /** With a validated talker on record the floor state already says who; otherwise it is just busy. */
        private fun refusedBusy() {
            mutableState.update { if (it.floor == TalkFloor.IDLE) it.copy(floor = TalkFloor.BUSY) else it }
            mutableEvents.tryEmit(TalkEvent.FloorBusy)
        }

        /** `floor_busy` or `error` for the request: its buffer is discarded unsent, the id never used again. */
        private fun failOutstanding() {
            val t = own?.takeIf { !it.granted && !it.awaitingResume } ?: return
            audio?.onRequestFailed(t.id)
            freeOwn(t)
        }

        @Suppress("ReturnCount")
        private fun released(s: TalkSocket, m: ServerMessage.FloorReleased) {
            var yours = false
            var mine = false
            synchronized(audioLock) {
                val t = own?.takeIf { it.id == m.transmissionId }
                if (t != null && t.resumable && m.reason != ReleaseReason.RELEASED) {
                    floorLost(t)
                    return
                }
                audio?.onFloorReleased(m.transmissionId)?.let { sendHeard(s, m.transmissionId, it) }
                if (t != null) {
                    // Closed (protocol 2), or the floor of a protocol-1 transmission: either way it is over.
                    mine = t.granted && t.capturing
                    freeOwn(t)
                }
                if (m.transmissionId == floorId) {
                    yours = mutableState.value.floor == TalkFloor.YOU
                    floorId = null
                    mutableState.update { it.copy(floor = TalkFloor.IDLE) }
                } else if (!mine) {
                    return
                }
            }
            if (mine) mutableEvents.tryEmit(TalkEvent.StopCapture)
            if (mine || yours) {
                mutableEvents.tryEmit(TalkEvent.FloorReleased(yours = true))
                return
            }
            peerStoppedTalking()
            playback.tryEmit(LiveEnded(m.transmissionId))
            mutableEvents.tryEmit(TalkEvent.FloorReleased(yours = false))
        }

        /** `silence` or `max_length` for the own protocol-2 transmission: the floor is gone, the sending goes on. */
        private fun floorLost(t: TalkerTransmission) {
            audio?.onFloorReleased(t.id, ownStaysOpen = true)
            t.onFloorLost()
            if (floorId == t.id) {
                floorId = null
                mutableState.update { if (it.floor == TalkFloor.YOU) it.copy(floor = TalkFloor.IDLE) else it }
            }
            publishOwn()
        }

        private fun listenerGap(transmissionId: String) = synchronized(audioLock) {
            if (own?.id != transmissionId) return@synchronized
            lastNotice = OwnNotice.NOT_HEARD_LIVE
            publishOwn()
        }

        /** An accepted `clip` (section 13.8); one that ends the peer's current transmission counts as its release. */
        private fun clip(s: TalkSocket, m: ServerMessage.Clip) {
            var endedFloor = false
            val verdict = synchronized(audioLock) {
                val v = audio?.onClip(m.talker, m.transmissionId, m.lastSeq) as? ClipVerdict.Accepted ?: return
                if (peerDevice == null) peerDevice = audio?.peer
                v.heard?.let { sendHeard(s, m.transmissionId, it) }
                if (v.endedCurrent && floorId == m.transmissionId) {
                    endedFloor = true
                    floorId = null
                    mutableState.update { it.copy(floor = TalkFloor.IDLE) }
                }
                v
            }
            // A clip is a finished transmission of the peer's, so it satisfies the first-talker rule (section 13.7).
            peerStoppedTalking()
            if (verdict.endedCurrent) playback.tryEmit(LiveEnded(m.transmissionId))
            playback.tryEmit(ClipStarted(m.transmissionId, m.lastSeq, verdict.acceptedBefore))
            if (endedFloor) mutableEvents.tryEmit(TalkEvent.FloorReleased(yours = false))
        }

        /** `heard` exists only on a joined protocol-2 socket (section 13.5). */
        private fun sendHeard(s: TalkSocket, transmissionId: String, lastSeq: Int) {
            if (protocol2 && joined) s.send(ClientMessage.Heard(transmissionId, lastSeq))
        }

        private suspend fun error(s: TalkSocket, code: WsErrorCode) {
            when (code) {
                WsErrorCode.NOT_FOUND -> if (!joined) joinRefused(s)

                WsErrorCode.NO_PEER -> {
                    synchronized(audioLock) { failOutstanding() }
                    mutableEvents.tryEmit(TalkEvent.NoPeer)
                }

                WsErrorCode.BAD_TRANSMISSION_ID -> synchronized(audioLock) {
                    val resumed = own?.takeIf { resumeSent && it.awaitingResume }
                    if (resumed != null) resumeRefused(resumed) else failOutstanding()
                }

                WsErrorCode.UNKNOWN -> synchronized(audioLock) { failOutstanding() }
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
                sendJoin(s)
            }
        }

        private suspend fun endSession(reason: SessionEndReason) {
            if (ended && mutableState.value.stage == SessionStage.ENDED) return
            ended = true
            // No more sealing from here on, whatever the database write below takes.
            val peerWasTalking = synchronized(audioLock) {
                pendingPress = false
                own?.let(::freeOwn)
                mutableState.value.floor == TalkFloor.PEER_TALKING
            }
            if (peerWasTalking) peerStoppedTalking()
            mutableState.update { it.copy(stage = SessionStage.ENDED, floor = TalkFloor.IDLE, peerPresent = false) }
            sessionKeys.clear(pageId)
            withContext(NonCancellable) { pageEvents.sessionEnded(pageId, role, reason) }
            mutableEvents.tryEmit(TalkEvent.SessionEnded(reason))
            socket?.close()
        }

        private fun onFrame(s: TalkSocket, bytes: ByteArray) {
            val verdict = audio?.onFrame(bytes) ?: return
            // Only authenticated, first-time frames are played; everything else is dropped silently.
            if (verdict is FrameVerdict.Accepted) {
                playback.tryEmit(IncomingOpus(verdict.transmissionId, verdict.seq, verdict.opus))
                verdict.heard?.let { sendHeard(s, verdict.transmissionId, it) }
            }
        }

        // --- the talk button and the own transmission ---

        private fun canRequest() = !ended && joined && socket != null && audio != null

        @Suppress("ReturnCount")
        fun press(): Boolean = synchronized(audioLock) {
            if (pressed || !canRequest()) return false
            pressed = true
            lastNotice = OwnNotice.NONE
            if (own != null) {
                // Section 13.6: the request waits until the previous transmission is sent and closed.
                pendingPress = true
                publishOwn()
                return true
            }
            if (!request()) pressed = false
            pressed
        }

        /** Sends the `floor_request` of a new own transmission. Under [audioLock]. */
        @Suppress("ReturnCount")
        private fun request(): Boolean {
            val s = socket
            val a = audio
            if (!canRequest() || s == null || a == null) return false
            val id = try {
                a.requestFloor()
            } catch (_: ProtocolException) {
                return false
            }
            // Live mode: `clip: false` on protocol 2 (section 13.9); the key is not allowed on protocol 1.
            if (!s.send(ClientMessage.FloorRequest(id, clip = if (protocol2) false else null))) {
                a.onRequestFailed(id)
                return false
            }
            own = TalkerTransmission(id, protocol2)
            mutableState.update { if (it.floor == TalkFloor.BUSY) it.copy(floor = TalkFloor.IDLE) else it }
            publishOwn()
            return true
        }

        fun release() = synchronized(audioLock) {
            pressed = false
            pendingPress = false
            audio?.buttonReleased()
            // A request still awaiting its grant needs nothing here: the floor answers that grant with a release.
            own?.takeIf { it.granted && it.capturing }?.let(::finishCapture)
            publishOwn()
        }

        /**
         * Capture of [t] ends (the button went up, or seq 2999 was sealed): the rest is sent, then `floor_release`
         * (section 13.6). A protocol-1 transmission sends its release at once and is over (milestone 4).
         */
        private fun finishCapture(t: TalkerTransmission) {
            t.stopCapture()
            if (floorId == t.id) floorId = null
            mutableState.update { if (it.floor == TalkFloor.YOU) it.copy(floor = TalkFloor.IDLE) else it }
            if (t.protocol2) {
                sendBacklog()
            } else {
                socket?.send(ClientMessage.FloorRelease(t.id))
                freeOwn(t)
            }
        }

        @Suppress("ReturnCount")
        fun sendOpus(packet: ByteArray): Boolean = synchronized(audioLock) {
            val t = own ?: return false
            val before = t.next
            val sealed = try {
                t.seal(packet)
            } catch (_: ProtocolException) {
                release()
                mutableEvents.tryEmit(TalkEvent.StopCapture)
                return false
            }
            if (!sealed) return false
            if (t.capturing) {
                sendBacklog()
            } else {
                finishCapture(t)
                mutableEvents.tryEmit(TalkEvent.StopCapture)
            }
            publishOwn()
            // Protocol 2 keeps the frame for a later send; protocol 1 reports whether it went out (milestone 4).
            t.protocol2 || t.next > before
        }

        /**
         * Hands the own transmission's frames to the socket in seq order, as section 13.6 allows: at most 200 in any
         * second, a backlog frame only while under 16 KB are queued unsent; then `floor_release` once capture is
         * over and every frame was handed. Waits are retried by a drain coroutine. Under [audioLock].
         */
        @Suppress("ReturnCount")
        private fun sendBacklog() {
            val t = own ?: return
            val s = socket?.takeIf { joined } ?: return
            var frame = t.nextFrame()
            while (frame != null) {
                val now = nowMs()
                val wait = sendWait(t, s, now)
                if (wait > 0) return drainLater(wait)
                if (!s.sendFrame(frame)) return
                pacer.sent(now)
                t.handed()
                frame = t.nextFrame()
            }
            if (t.releaseDue && s.send(ClientMessage.FloorRelease(t.id))) t.onReleaseHanded()
        }

        /** How long the frame at `t.next` must wait: the 200 / s pacing, then the 16 KB rule for a backlog frame. */
        private fun sendWait(t: TalkerTransmission, s: TalkSocket, now: Long): Long {
            val paced = pacer.waitMs(now)
            return when {
                paced > 0 -> paced
                t.backlog && s.unsentBytes >= BACKLOG_LIMIT_BYTES -> QUEUE_POLL_MS
                else -> 0
            }
        }

        private fun drainLater(waitMs: Long) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch {
                delay(waitMs)
                synchronized(audioLock) {
                    drainJob = null
                    sendBacklog()
                    publishOwn()
                }
            }
        }

        /** After each join: a protocol-2 own transmission left by the old socket is resumed (section 13.6). */
        private fun afterJoin(s: TalkSocket) {
            val t = own
            when {
                t != null && t.awaitingResume && protocol2 && !resumeSent ->
                    resumeSent = s.send(ClientMessage.Resume(t.id))

                t == null && pendingPress -> firePendingPress()
            }
        }

        private fun resumeFrom(s: TalkSocket, lastSeq: Int) = synchronized(audioLock) {
            val t = own?.takeIf { resumeSent && it.awaitingResume } ?: return@synchronized
            resumeSent = false
            if (!t.granted) {
                // Granted while the socket was down and never seen here: nothing was captured, so it ends empty.
                s.send(ClientMessage.FloorRelease(t.id))
                freeOwn(t)
                return@synchronized
            }
            t.onResumeFrom(lastSeq)
            sendBacklog()
            publishOwn()
        }

        /** `bad_transmission_id` after `resume`: the server no longer holds it. */
        private fun resumeRefused(t: TalkerTransmission) {
            resumeSent = false
            giveUp(t)
        }

        /** The own transmission cannot be finished: it is freed, "Not sent" if the server may lack frames of it. */
        private fun giveUp(t: TalkerTransmission) {
            if (t.granted && t.mayLackFrames) lastNotice = OwnNotice.NOT_SENT
            val capturing = t.capturing
            if (capturing) pressed = false
            freeOwn(t)
            if (capturing) mutableEvents.tryEmit(TalkEvent.StopCapture)
        }

        /** The own transmission is over (closed, refused, given up): the buffer goes; a waiting press goes ahead. */
        private fun freeOwn(t: TalkerTransmission) {
            if (own !== t) return
            t.free()
            audio?.onOwnClosed(t.id)
            own = null
            resumeSent = false
            drainJob?.cancel()
            drainJob = null
            if (pendingPress && joined) firePendingPress()
            publishOwn()
        }

        private fun firePendingPress() {
            pendingPress = false
            if (pressed && !request()) pressed = false
        }

        /** Publishes the own transmission's part of the state. Under [audioLock]. */
        private fun publishOwn() {
            val t = own
            val asClip = t != null && t.sending && !t.floorHeld
            mutableState.update {
                if (run !== this) {
                    it
                } else {
                    it.copy(
                        capturing = t?.capturing == true,
                        sending = t?.sending == true,
                        ownNotice = if (asClip) OwnNotice.SENDING_AS_CLIP else lastNotice
                    )
                }
            }
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
            firstTalker?.let { synchronized(it) { lockTimer?.cancel() } }
            synchronized(audioLock) {
                pendingPress = false
                own?.free()
                own = null
                drainJob?.cancel()
            }
            audio?.close()
            socket?.close()
            socket = null
            mutableState.update {
                when {
                    it.pageId != pageId && it.pageId != null -> it

                    // Live talk is over but the page goes on: keep the stage, say "unavailable".
                    pageContinues -> it.copy(
                        floor = TalkFloor.IDLE,
                        peerPresent = false,
                        capturing = false,
                        sending = false
                    )

                    else -> it.copy(
                        stage = SessionStage.ENDED,
                        floor = TalkFloor.IDLE,
                        peerPresent = false,
                        capturing = false,
                        sending = false
                    )
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
        private const val ACCEPTED_MEMORY = 16
        private const val PROTOCOL_CLIP = 2

        /** A backlog frame goes to the socket only while fewer bytes than this are queued unsent (section 13.6). */
        const val BACKLOG_LIMIT_BYTES = 16 * 1024L

        /** How soon a backlog held by the 16 KB rule is tried again (one frame's time). */
        const val QUEUE_POLL_MS = 20L
        private const val STATE_ENDED = "ended"
        private const val STATE_ANSWERED = "answered"
        private val OVER_STATES = setOf("ended", "failed", "missed")
    }
}
