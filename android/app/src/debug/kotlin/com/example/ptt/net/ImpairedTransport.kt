package com.example.ptt.net

import android.util.Log
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Wraps [inner] so that [DebugImpairment] applies to the talk WebSocket. */
fun impairTransport(inner: TalkTransport): TalkTransport =
    ImpairedTransport(inner, { DebugImpairment.state.value }, Random.Default)

/**
 * Delays every message of each direction by [Impairment.delayMs] and drops [Impairment.lossPct] percent of received
 * binary frames. Text messages are never dropped, outgoing messages are never dropped, and order is kept per
 * direction even when the delay changes between messages. With nothing set it calls straight through.
 */
internal class ImpairedTransport(
    private val inner: TalkTransport,
    private val setting: () -> Impairment,
    private val random: Random,
    private val nanoTime: () -> Long = System::nanoTime
) : TalkTransport {
    private val incoming = Lane("ptt-impair-in", nanoTime)
    private val outgoing = Lane("ptt-impair-out", nanoTime)

    override suspend fun open(listener: TransportListener): TransportSocket? {
        val socket = ImpairedSocket()
        val opened = inner.open(Receiving(socket, listener)) ?: return null
        socket.attach(opened)
        return socket
    }

    private inner class Receiving(private val wrapper: ImpairedSocket, private val listener: TransportListener) :
        TransportListener {
        override fun onOpen(socket: TransportSocket) {
            wrapper.attach(socket)
            deliver { listener.onOpen(wrapper) }
        }

        override fun onText(text: String) = deliver { listener.onText(text) }

        override fun onBinary(bytes: ByteArray) {
            if (random.nextInt(PERCENT) < setting().lossPct) return
            deliver { listener.onBinary(bytes) }
        }

        override fun onClosed(code: Int?, httpStatus: Int?) = deliver { listener.onClosed(code, httpStatus) }

        private fun deliver(task: () -> Unit) = incoming.submit(setting().delayMs) {
            if (!wrapper.cancelled) task()
        }
    }

    private inner class ImpairedSocket : TransportSocket {
        @Volatile
        private var target: TransportSocket? = null

        @Volatile
        var cancelled = false
            private set
        private val pendingBytes = AtomicLong()

        fun attach(socket: TransportSocket) {
            if (target == null) target = socket
        }

        override fun sendText(text: String) = send(0) { it.sendText(text) }

        override fun sendBinary(bytes: ByteArray) = send(bytes.size.toLong()) { it.sendBinary(bytes) }

        override fun queuedBytes() = (target?.queuedBytes() ?: 0L) + pendingBytes.get()

        override fun close(code: Int) {
            outgoing.submit(setting().delayMs) { target?.close(code) }
        }

        override fun cancel() {
            cancelled = true
            target?.cancel()
        }

        private fun send(size: Long, action: (TransportSocket) -> Boolean): Boolean {
            val socket = target
            if (cancelled || socket == null) return false
            val delayMs = setting().delayMs
            if (!outgoing.mustQueue(delayMs)) return action(socket)
            pendingBytes.addAndGet(size)
            outgoing.submit(delayMs) {
                pendingBytes.addAndGet(-size)
                if (!cancelled) action(socket)
            }
            return true
        }
    }

    /** One direction: tasks run in submission order, each no earlier than its delay after it was submitted. */
    private class Lane(name: String, private val nanoTime: () -> Long) {
        private val queue = DelayQueue<Item>()
        private val seq = AtomicLong()
        private var lastDueNanos = Long.MIN_VALUE
        private var pending = 0

        init {
            Thread({
                while (true) {
                    val item = queue.take()
                    try {
                        item.task()
                    } catch (e: Exception) {
                        Log.w(TAG, "impaired task failed: ${e.javaClass.simpleName}")
                    } finally {
                        synchronized(this) { pending-- }
                    }
                }
            }, name).also {
                it.isDaemon = true
                it.start()
            }
        }

        /** True when a task submitted now with [delayMs] cannot run inline without overtaking a queued one. */
        @Synchronized
        fun mustQueue(delayMs: Int) = delayMs > 0 || pending > 0

        fun submit(delayMs: Int, task: () -> Unit) {
            synchronized(this) {
                if (delayMs <= 0 && pending == 0) {
                    null
                } else {
                    val due = maxOf(nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs.toLong()), lastDueNanos)
                    lastDueNanos = due
                    pending++
                    Item(due, seq.getAndIncrement(), task, nanoTime).also(queue::add)
                }
            } ?: task()
        }
    }

    private class Item(
        private val dueNanos: Long,
        private val order: Long,
        val task: () -> Unit,
        private val nanoTime: () -> Long
    ) : Delayed {
        override fun getDelay(unit: TimeUnit) = unit.convert(dueNanos - nanoTime(), TimeUnit.NANOSECONDS)

        override fun compareTo(other: Delayed): Int {
            other as Item
            return compareValuesBy(this, other, { it.dueNanos }, { it.order })
        }
    }

    private companion object {
        const val PERCENT = 100
        const val TAG = "ImpairedTransport"
    }
}
