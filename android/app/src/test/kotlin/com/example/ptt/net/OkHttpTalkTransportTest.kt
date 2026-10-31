package com.example.ptt.net

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real OkHttp WebSocket against a local MockWebServer: hello first, no credentials in the upgrade. */
class OkHttpTalkTransportTest {
    private val server = MockWebServer()
    private val state = FakeEnrollmentState()
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var transport: OkHttpTalkTransport

    @Before
    fun setUp() {
        server.start()
        state.url = server.url("/").toString().trimEnd('/')
        val client = HttpClientFactory.create(
            AuthInterceptor(InMemoryDeviceTokenStore(WS_TOKEN, OWN), state),
            debug = true,
            log = {}
        )
        transport = OkHttpTalkTransport(client, state)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun socket() = TalkSocket(transport, FakeTalkAuth(), "0.3.0", scope, System::currentTimeMillis)

    @Test(timeout = 60_000)
    fun theFirstFrameIsHelloAndTheUpgradeCarriesNoCredentials() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    if ("\"hello\"" in text) webSocket.send(serverHello())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
            }).build()
        )
        val s = socket()
        s.open()
        val ready = withTimeout(30_000) { s.events.receive() }
        assertEquals(SocketEvent.Ready(OWN, 1), ready)
        val request = server.takeRequest()
        assertEquals("/v1/ws", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
        val first = received.poll(10, TimeUnit.SECONDS)!!
        assertTrue(first.startsWith("""{"type":"hello""""))
        s.close()
    }

    @Test(timeout = 60_000)
    fun http404OnUpgradeIsTalkUnavailable() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        val s = socket()
        s.open()
        val closed = withTimeout(30_000) { s.events.receive() } as SocketEvent.Closed
        assertEquals(CloseReaction.UNAVAILABLE, closed.reaction)
    }

    @Test(timeout = 60_000)
    fun aServerCloseCodeIsReported() = runBlocking {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.close(4409, "replaced")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
            }).build()
        )
        val s = socket()
        s.open()
        val closed = withTimeout(30_000) { s.events.receive() } as SocketEvent.Closed
        assertEquals(CloseReaction.REPLACED, closed.reaction)
        assertEquals(4409, closed.code)
    }
}
