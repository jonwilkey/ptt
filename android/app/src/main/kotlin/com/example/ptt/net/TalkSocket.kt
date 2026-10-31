package com.example.ptt.net

import com.example.ptt.crypto.ProtocolException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Callbacks of one physical WebSocket, in socket order. Called from the transport's reader thread. */
interface TransportListener {
    /** The upgrade succeeded; [socket] is the open connection (valid before [TalkTransport.open] returns). */
    fun onOpen(socket: TransportSocket)

    fun onText(text: String)

    fun onBinary(bytes: ByteArray)

    /** The socket is finished. [code] is the close code when the peer closed it, [httpStatus] a failed upgrade's. */
    fun onClosed(code: Int?, httpStatus: Int?)
}

interface TransportSocket {
    /** Queues a text frame; false if the socket cannot take it. */
    fun sendText(text: String): Boolean

    fun sendBinary(bytes: ByteArray): Boolean

    /** A graceful close with [code]. */
    fun close(code: Int)

    /** Drops the connection at once. */
    fun cancel()
}

/** Opens a WebSocket to the enrolled server's `/v1/ws`. The abstraction exists so tests need no network. */
fun interface TalkTransport {
    /** Null when the device is not enrolled (no server URL). */
    suspend fun open(listener: TransportListener): TransportSocket?
}

/**
 * OkHttp's WebSocket over the app's one client (same TLS 1.3 connection spec and host rules), derived so that
 * it carries no interceptor (the upgrade has no credentials, section 11.1: the token goes in the first frame)
 * and no read or call timeout (the connection idles between frames; liveness is [TalkSocket]'s ping).
 */
class OkHttpTalkTransport(client: OkHttpClient, private val state: EnrollmentState) : TalkTransport {
    private val wsClient: OkHttpClient = client.newBuilder()
        .apply { interceptors().clear() }
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun open(listener: TransportListener): TransportSocket? {
        val base = state.serverUrl() ?: return null
        val request = Request.Builder().url("$base/v1/ws").build()
        return OkSocket(wsClient.newWebSocket(request, Adapter(listener)))
    }

    private class OkSocket(private val ws: WebSocket) : TransportSocket {
        override fun sendText(text: String) = ws.send(text)

        override fun sendBinary(bytes: ByteArray) = ws.send(bytes.toByteString())

        override fun close(code: Int) {
            ws.close(code, null)
        }

        override fun cancel() = ws.cancel()
    }

    private class Adapter(private val listener: TransportListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen(OkSocket(webSocket))

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Complete the close handshake; the app learns of the code now.
            webSocket.close(NORMAL_CLOSE, null)
            listener.onClosed(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, null)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            listener.onClosed(null, response?.code)
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
    }
}

/** The device token for `hello`, and what to do when the server rejects it (same rule as the REST 401). */
interface TalkAuth {
    /** The token, or null when none can be read. */
    fun token(): String?

    /** The server closed with 4401/4403 after [sentToken]: flag re-enrollment unless the token has since changed. */
    fun rejected(sentToken: String)
}

class DeviceTalkAuth(private val tokens: DeviceTokenStore, private val state: EnrollmentState) : TalkAuth {
    override fun token(): String? = try {
        tokens.token()
    } catch (_: DeviceTokenUnavailable) {
        null
    }

    override fun rejected(sentToken: String) {
        // Same guard as AuthInterceptor: a late close for an old identity must not flag a fresh enrollment.
        if (token() == sentToken) state.markNeedsReenrollment()
    }
}

/** What a [TalkSocket] reports, in socket order, on [TalkSocket.events]. */
sealed interface SocketEvent {
    /** `hello` was answered: the socket is authenticated. [protocol] is the version both sides now speak. */
    data class Ready(val deviceId: String, val protocol: Int) : SocketEvent

    data class Message(val message: ServerMessage) : SocketEvent

    class Frame(val bytes: ByteArray) : SocketEvent

    /** A round-trip time measured from a `pong`. */
    data class Rtt(val millis: Long) : SocketEvent

    /** The last event of a socket. */
    data class Closed(val reaction: CloseReaction, val code: Int?, val httpStatus: Int?) : SocketEvent
}

/** What the client does after a close (docs/protocol.md section 11.1 table). */
enum class CloseReaction {
    /** 4401, 4403: stop, treat like REST 401. */
    UNAUTHORIZED,

    /** 4409: another socket of this device took over; never reconnect on our own. */
    REPLACED,

    /** The upgrade got HTTP 404: this server has no live talk. Paging still works. */
    UNAVAILABLE,

    /** Everything else: reconnect with backoff if the page or session is still active. */
    RECONNECT;

    companion object {
        const val UNAUTHORIZED_CODE = 4401
        const val REVOKED_CODE = 4403
        const val REPLACED_CODE = 4409
        const val HTTP_NOT_FOUND = 404

        /**
         * The reaction to a close with [code] (null: the network dropped or the upgrade failed) and the HTTP status
         * of a failed upgrade. 1000, 4400, 4408, 4413, 4429, 4500, 4503 and any unlisted code reconnect.
         */
        fun of(code: Int?, httpStatus: Int?): CloseReaction = when {
            code == UNAUTHORIZED_CODE || code == REVOKED_CODE -> UNAUTHORIZED
            code == REPLACED_CODE -> REPLACED
            code == null && httpStatus == HTTP_NOT_FOUND -> UNAVAILABLE
            else -> RECONNECT
        }
    }
}

/**
 * One physical WebSocket to the server: sends `hello` first (strict codec), reports the server's messages in
 * order on [events], pings every [PING_INTERVAL_MS] for RTT and liveness, and ends with exactly one
 * [SocketEvent.Closed]. Reconnecting is the caller's business (a new [TalkSocket] each time). Never logs.
 */
class TalkSocket(
    private val transport: TalkTransport,
    private val auth: TalkAuth,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) {
    private val channel = Channel<SocketEvent>(Channel.UNLIMITED)

    /** Every event, in socket order; closed after [SocketEvent.Closed]. */
    val events: ReceiveChannel<SocketEvent> = channel

    private val finished = AtomicBoolean(false)

    @Volatile
    private var socket: TransportSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var lastInboundMs = 0L

    @Volatile
    private var sentToken: String? = null
    private var helloTimeout: Job? = null
    private var pinger: Job? = null
    private val started = AtomicBoolean(false)

    /** Opens the connection. Once. */
    fun open() {
        check(started.compareAndSet(false, true)) { "opened twice" }
        scope.launch {
            val token = auth.token()
            if (token == null) {
                finish(CloseReaction.UNAUTHORIZED, null, null, notifyAuth = false)
                return@launch
            }
            sentToken = token
            val opened = try {
                transport.open(Listener())
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                null
            }
            if (opened == null) {
                // Not enrolled (no server URL): there is nothing to connect to.
                finish(CloseReaction.UNAVAILABLE, null, null)
                return@launch
            }
            if (socket == null) socket = opened
            if (finished.get()) opened.cancel()
        }
    }

    /** Sends a control message; false if the socket is not open (or the message is one the server would refuse). */
    @Suppress("ReturnCount")
    fun send(message: ClientMessage): Boolean {
        if (message !is ClientMessage.Hello && !ready) return false
        val text = try {
            TalkCodec.encode(message)
        } catch (_: ProtocolException) {
            return false
        }
        return socket?.sendText(text) ?: false
    }

    /** Sends one sealed audio frame (a binary frame). */
    fun sendFrame(frame: ByteArray): Boolean = ready && (socket?.sendBinary(frame) ?: false)

    /** Closes gracefully (client-initiated, code 1000); no further events except the final [SocketEvent.Closed]. */
    fun close() {
        val s = socket
        if (s == null) {
            finish(CloseReaction.RECONNECT, NORMAL_CLOSE, null)
            return
        }
        // Graceful, so a message sent just before (session_end) is flushed; the grace timer bounds the wait.
        s.close(NORMAL_CLOSE)
        scope.launch {
            delay(CLOSE_GRACE_MS)
            finish(CloseReaction.RECONNECT, NORMAL_CLOSE, null)
        }
    }

    private fun finish(reaction: CloseReaction, code: Int?, httpStatus: Int?, notifyAuth: Boolean = true) {
        if (!finished.compareAndSet(false, true)) return
        ready = false
        helloTimeout?.cancel()
        pinger?.cancel()
        if (notifyAuth && reaction == CloseReaction.UNAUTHORIZED) sentToken?.let(auth::rejected)
        sentToken = null
        socket?.cancel()
        channel.trySend(SocketEvent.Closed(reaction, code, httpStatus))
        channel.close()
    }

    private inner class Listener : TransportListener {
        override fun onOpen(socket: TransportSocket) {
            val token = sentToken ?: return
            this@TalkSocket.socket = socket
            lastInboundMs = nowMs()
            // The first frame, always (docs/protocol.md section 11.3).
            socket.sendText(TalkCodec.encode(ClientMessage.Hello(TALK_PROTOCOL, token, appVersion)))
            helloTimeout = scope.launch {
                delay(HELLO_TIMEOUT_MS)
                finish(CloseReaction.RECONNECT, null, null)
            }
        }

        @Suppress("ReturnCount")
        override fun onText(text: String) {
            lastInboundMs = nowMs()
            val message = TalkCodec.decode(text) ?: return
            if (message is ServerMessage.Hello) {
                if (ready) return
                ready = true
                helloTimeout?.cancel()
                channel.trySend(SocketEvent.Ready(message.deviceId, minOf(message.protocol, TALK_PROTOCOL)))
                startPings()
                return
            }
            if (!ready) return
            if (message is ServerMessage.Pong) {
                channel.trySend(SocketEvent.Rtt((nowMs() - message.t).coerceAtLeast(0)))
                return
            }
            channel.trySend(SocketEvent.Message(message))
        }

        override fun onBinary(bytes: ByteArray) {
            lastInboundMs = nowMs()
            if (ready) channel.trySend(SocketEvent.Frame(bytes))
        }

        override fun onClosed(code: Int?, httpStatus: Int?) {
            finish(CloseReaction.of(code, httpStatus), code, httpStatus)
        }
    }

    private fun startPings() {
        pinger = scope.launch {
            while (true) {
                send(ClientMessage.Ping(nowMs()))
                delay(PING_INTERVAL_MS)
                if (nowMs() - lastInboundMs > DEAD_AFTER_MS) {
                    finish(CloseReaction.RECONNECT, null, null)
                    return@launch
                }
            }
        }
    }

    companion object {
        const val PING_INTERVAL_MS = 10_000L
        const val HELLO_TIMEOUT_MS = 10_000L

        /** No message of any kind from the server for this long: the connection is dead. */
        const val DEAD_AFTER_MS = 30_000L
        private const val NORMAL_CLOSE = 1000
        private const val CLOSE_GRACE_MS = 2_000L
    }
}
