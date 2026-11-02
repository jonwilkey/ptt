package com.example.ptt.talk

import com.example.ptt.crypto.FrameSealer

/**
 * One transmission of this phone's own, from its `floor_request` until it is closed or given up: the talker's
 * buffer of docs/protocol.md section 13.6. It holds the sealed bytes of every frame (sealed once, at capture, by the
 * [FrameSealer] of the grant) and which of them have been handed to the socket, so a send after `resume_from`
 * hands over the same bytes again and never seals anything twice (section 12.4 rule 3).
 *
 * Pure state, no I/O: [TalkSessionController] serializes access and does the sending.
 *
 * A [protocol2] transmission survives the loss of its floor ([onFloorLost]) and of its socket ([onSocketLost]); a
 * protocol-1 one ends with either (milestone 4).
 */
class TalkerTransmission(val id: String, val protocol2: Boolean) {
    private var sealer: FrameSealer? = null

    /** The grant answered the request: frames can be sealed and sent. */
    val granted: Boolean get() = sealer != null

    /** It outlives the loss of its floor and of its socket (a granted protocol-2 transmission, section 13.6). */
    val resumable: Boolean get() = protocol2 && granted

    /** Frames are being captured into the buffer (the button is held, the cap not reached). */
    var capturing = false
        private set

    /** The seq of the next frame to hand to the socket. */
    var next = 0
        private set

    /** This phone holds the floor for it (granted, and not released by the server or lost with the socket). */
    var floorHeld = false
        private set

    var releaseHanded = false
        private set

    /** The socket closed: nothing of it is sent until a `resume_from` answers the `resume` (section 13.6). */
    var awaitingResume = false
        private set

    /** The highest seq the server is known to hold (from `resume_from`), -1 for none. */
    private var confirmed = -1

    /** How many frames have been sealed. */
    val sealed: Int get() = sealer?.sealedCount ?: 0

    /** Still capturing, or not everything (frames, then `floor_release`) is handed to the socket yet. */
    val sending: Boolean get() = granted && (capturing || next < sealed || !releaseHanded || awaitingResume)

    /** The frame at [next] is a backlog frame, not the one just captured (the 16 KB rule applies to it). */
    val backlog: Boolean get() = !(capturing && next == sealed - 1)

    /** Every frame and then the `floor_release` may go: capture is over and every frame was handed. */
    val releaseDue: Boolean get() = granted && !capturing && next == sealed && !releaseHanded && !awaitingResume

    /** The server may lack frames of it: some were sealed beyond what a `resume_from` vouched for. */
    val mayLackFrames: Boolean get() = sealed - 1 > confirmed

    /** The grant: capture starts (the request was still wanted, or the grant would have been released). */
    fun onGranted(s: FrameSealer) {
        sealer = s
        capturing = true
        floorHeld = true
    }

    /**
     * Seals one captured Opus packet into the buffer; false when not capturing. Sealing frame [MAX_FRAMES] - 1
     * (seq 2999) ends the capture.
     */
    fun seal(packet: ByteArray): Boolean {
        val s = sealer
        if (!capturing || s == null) return false
        s.seal(packet)
        if (s.sealedCount >= MAX_FRAMES) stopCapture()
        return true
    }

    /** The button went up, the cap was reached, or capture failed: no more frames. The key is wiped now. */
    fun stopCapture() {
        capturing = false
        sealer?.close()
    }

    /** The next frame to hand to the socket (the stored sealed bytes), or null when none may go now. */
    fun nextFrame(): ByteArray? = if (awaitingResume || next >= sealed) null else sealer?.stored(next)

    /** The frame from [nextFrame] was handed to the socket. */
    fun handed() {
        next++
    }

    fun onReleaseHanded() {
        releaseHanded = true
    }

    /** `floor_released` `silence` or `max_length`: the floor is gone, the transmission stays open. */
    fun onFloorLost() {
        floorHeld = false
    }

    /** The socket closed: wait for the resume; the `floor_release` (if any was handed) may not have arrived. */
    fun onSocketLost() {
        floorHeld = false
        awaitingResume = true
        releaseHanded = false
    }

    /**
     * `resume_from {last_seq}`: send from `last_seq + 1`. A lower value than expected only resends the same bytes;
     * a value past what was sealed (a lying server) is treated as everything sealed.
     */
    fun onResumeFrom(lastSeq: Int) {
        awaitingResume = false
        next = (lastSeq + 1).coerceIn(0, sealed)
        confirmed = maxOf(confirmed, minOf(lastSeq, sealed - 1))
    }

    /** The transmission is over for this phone: no more sealing; the stored frames go with this object. */
    fun free() {
        capturing = false
        sealer?.close()
    }

    companion object {
        /** A transmission has at most 3000 frames, seq 0 to 2999 (section 13.10). */
        const val MAX_FRAMES = 3000
    }
}

/**
 * The talker's send rate (docs/protocol.md section 13.10): at most [limit] frames in any [windowMs], live frames and
 * resends together. Pure: every call passes `now`.
 */
class SendPacer(private val limit: Int = LIMIT, private val windowMs: Long = WINDOW_MS) {
    private val times = LongArray(limit)
    private var count = 0
    private var head = 0

    /** How long to wait before the next frame may be sent; 0 when it may go now. */
    fun waitMs(now: Long): Long {
        if (count < limit) return 0
        val oldest = times[head]
        return (oldest + windowMs - now).coerceAtLeast(0)
    }

    /** A frame was sent at [now] (only after [waitMs] said 0). */
    fun sent(now: Long) {
        times[head] = now
        head = (head + 1) % limit
        if (count < limit) count++
    }

    companion object {
        const val LIMIT = 200
        const val WINDOW_MS = 1_000L
    }
}
