package com.example.ptt.net

const val WS_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
const val OWN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const val PEER_DEVICE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** A scripted WebSocket: the test plays the server. */
class FakeWs(private val listener: TransportListener) : TransportSocket {
    val texts = mutableListOf<String>()
    val binaries = mutableListOf<ByteArray>()
    var cancelled = false
    var closedWith: Int? = null
    private var done = false

    fun serverOpen() = listener.onOpen(this)

    fun serverText(text: String) = listener.onText(text)

    fun serverBinary(bytes: ByteArray) = listener.onBinary(bytes)

    fun serverClose(code: Int) {
        done = true
        listener.onClosed(code, null)
    }

    fun failUpgrade(httpStatus: Int?) {
        done = true
        listener.onClosed(null, httpStatus)
    }

    override fun sendText(text: String): Boolean {
        if (done) return false
        texts += text
        return true
    }

    override fun sendBinary(bytes: ByteArray): Boolean {
        if (done) return false
        binaries += bytes
        return true
    }

    override fun close(code: Int) {
        closedWith = code
        if (!done) {
            done = true
            listener.onClosed(code, null)
        }
    }

    override fun cancel() {
        cancelled = true
    }

    /** The `type` of each text frame the phone sent. */
    fun types(): List<String> = texts.map { Regex("\"type\":\"([a-z_]+)\"").find(it)!!.groupValues[1] }

    fun lastOf(type: String): String = texts.last { "\"type\":\"$type\"" in it }
}

class FakeTalkTransport : TalkTransport {
    val sockets = mutableListOf<FakeWs>()
    var enrolled = true

    override suspend fun open(listener: TransportListener): TransportSocket? {
        if (!enrolled) return null
        return FakeWs(listener).also { sockets += it }
    }
}

class FakeTalkAuth(var token: String? = WS_TOKEN) : TalkAuth {
    val rejected = mutableListOf<String>()

    override fun token(): String? = token

    override fun rejected(sentToken: String) {
        rejected += sentToken
    }
}

fun serverHello(device: String = OWN, protocol: Int = 1) =
    """{"type":"hello","protocol":$protocol,"device_id":"$device","server_time":1000}"""
