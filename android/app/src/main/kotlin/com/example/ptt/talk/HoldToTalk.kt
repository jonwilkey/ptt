package com.example.ptt.talk

import com.example.ptt.audio.CaptureStatus
import com.example.ptt.audio.TalkAudio
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What the talk button needs of the live audio: the part of [com.example.ptt.audio.TalkAudio] it uses. */
interface TalkAudioPort {
    fun press(): Boolean

    fun release()

    val captureStatus: StateFlow<CaptureStatus>
}

/** Finds the audio of [pageId]'s session; null when the live-talk service does not host that page. */
interface TalkAudioSource {
    fun audio(pageId: String): TalkAudioPort?

    /** [audio] now and again whenever the hosting changes (the service starts after the screen). */
    fun audios(pageId: String): Flow<TalkAudioPort?>
}

/** Production: the audio of the running [TalkService], only if it hosts exactly [pageId]. */
class ServiceTalkAudioSource @Inject constructor() : TalkAudioSource {
    private var last: Pair<TalkAudio, TalkAudioPort>? = null

    override fun audios(pageId: String): Flow<TalkAudioPort?> =
        TalkService.hostedChanges.map { audio(pageId) }.distinctUntilChanged()

    @Synchronized
    @Suppress("ReturnCount")
    override fun audio(pageId: String): TalkAudioPort? {
        val service = TalkService.current
        val audio = service?.audio
        if (service == null || audio == null) {
            // The service or its audio is gone: let go of the closed audio.
            last = null
            return null
        }
        if (service.pageId != pageId) return null
        // One port per audio, so that following the status can tell "the same audio" from a new one.
        last?.takeIf { it.first === audio }?.let { return it.second }
        val port = object : TalkAudioPort {
            override fun press() = audio.press()

            override fun release() = audio.release()

            override val captureStatus get() = audio.captureStatus
        }
        last = audio to port
        return port
    }
}

/** The capture status of [pageId]'s audio, followed as the audio appears or is replaced; IDLE while there is none. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TalkAudioSource.captureStatus(pageId: String): Flow<CaptureStatus> =
    audios(pageId).flatMapLatest { it?.captureStatus ?: flowOf(CaptureStatus.IDLE) }

/**
 * The state of the hold-to-talk button, kept out of the composition so that holding never depends on
 * recomposition: whoever ends the hold (pointer up or cancel, focus loss, stop, talk disabled) calls [release].
 * Main thread only.
 *
 * It only calls [TalkAudioPort.press] when the caller says talk is enabled (a press briefly opens the mic and the
 * encoder), never twice for one hold, and never presses again by itself: after the audio stops on its own (cap,
 * mic, codec, focus, refused) the user must lift and press again. Never calls the controller directly: the audio
 * pre-warms the mic and owns audio focus.
 */
class HoldToTalk(private val source: TalkAudioSource) {
    private var held: TalkAudioPort? = null

    val isHeld: Boolean get() = held != null

    /** True if a request was made. False when disabled, already held, or the audio refused (not a request). */
    @Suppress("ReturnCount")
    fun press(pageId: String, enabled: Boolean): Boolean {
        if (!enabled || held != null) return false
        val audio = source.audio(pageId) ?: return false
        if (!audio.press()) return false
        held = audio
        return true
    }

    /** Ends the hold, if any. Idempotent. */
    fun release() {
        val audio = held ?: return
        held = null
        audio.release()
    }
}
