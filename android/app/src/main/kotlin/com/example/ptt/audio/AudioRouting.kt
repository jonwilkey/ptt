package com.example.ptt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** An audio output the phone could use for the conversation; [type] is an `AudioDeviceInfo.TYPE_*` constant. */
data class RouteDevice(val id: Int, val type: Int)

/** Where conversation audio goes. */
sealed interface Route {
    /** The loudspeaker (the walkie-talkie default). */
    data object Speaker : Route

    /** A wired headset, headphones or USB headset. */
    data class Wired(val device: RouteDevice) : Route

    /** A Bluetooth headset (SCO or LE audio). */
    data class Bluetooth(val device: RouteDevice) : Route
}

/** The routing decision, pure so it is testable with a fake device list. */
object RoutePolicy {
    // AudioDeviceInfo.TYPE_*, spelled out because some are newer than minSdk.
    private const val WIRED_HEADSET = 3
    private const val WIRED_HEADPHONES = 4
    private const val BLUETOOTH_SCO = 7
    const val HEARING_AID = 23
    private const val USB_HEADSET = 22
    private const val BLE_HEADSET = 26

    private val wired = setOf(WIRED_HEADSET, WIRED_HEADPHONES, USB_HEADSET)

    /** Bluetooth types in order of preference: LE audio, then classic SCO, then a hearing aid. */
    private val bluetooth = listOf(BLE_HEADSET, BLUETOOTH_SCO, HEARING_AID)

    /**
     * The loudspeaker unless something private is connected; then a wired headset, else Bluetooth. (A wired
     * headset is chosen first because plugging one in is a deliberate act and it has no pairing lag.)
     */
    @Suppress("ReturnCount")
    fun choose(devices: List<RouteDevice>): Route {
        devices.firstOrNull { it.type in wired }?.let { return Route.Wired(it) }
        for (type in bluetooth) {
            devices.firstOrNull { it.type == type }?.let { return Route.Bluetooth(it) }
        }
        return Route.Speaker
    }
}

/** The platform side of routing and focus (a fake in tests). */
interface AudioPlatform {
    /** The outputs that may carry a call right now. */
    fun devices(): List<RouteDevice>

    /** Switches to in-communication mode, remembering what to restore. */
    fun enter()

    fun apply(route: Route)

    /** Undoes [enter] and every [apply]. */
    fun restore()

    /** Calls [changed] whenever an output appears or disappears; close the result to stop. */
    fun watchDevices(changed: () -> Unit): AutoCloseable

    /** Takes transient audio focus; [lost] fires if it is taken away. False if refused. */
    fun requestFocus(lost: () -> Unit): Boolean

    fun abandonFocus()
}

/**
 * Keeps conversation audio on the right output for the length of a session: [start] enters communication mode,
 * takes audio focus and routes (speaker by default, a connected headset preferred), re-deciding whenever a device
 * connects or disconnects; [stop] restores the mode and routing and gives focus back.
 */
class AudioRouting(private val platform: AudioPlatform, private val onFocusLost: () -> Unit = {}) {
    private var watch: AutoCloseable? = null
    private var active = false
    private var terminal = false
    private var focusHeld = false

    /** The route in use, null when inactive. */
    @Volatile
    var route: Route? = null
        private set

    /** Starts routing; a no-op once [close]d. */
    @Synchronized
    fun start() {
        if (active || terminal) return
        active = true
        platform.enter()
        requestFocus()
        watch = platform.watchDevices(::refresh)
        refresh()
    }

    private fun requestFocus() {
        focusHeld = platform.requestFocus {
            synchronized(this) { focusHeld = false }
            onFocusLost()
        }
    }

    /**
     * Takes audio focus again if it was lost (a call ended, another app yielded). Called when a transmission starts
     * or is about to; there is no automatic return on focus gain because the session is user-driven.
     */
    @Synchronized
    fun ensureFocus() {
        if (active && !focusHeld) requestFocus()
    }

    @Synchronized
    fun refresh() {
        if (!active) return
        val next = RoutePolicy.choose(platform.devices())
        if (next != route) {
            route = next
            platform.apply(next)
        }
    }

    /** Stops for good: a later [start] does nothing (the session this belongs to is over). */
    @Synchronized
    fun close() {
        terminal = true
        stop()
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        focusHeld = false
        route = null
        watch?.close()
        watch = null
        platform.restore()
        platform.abandonFocus()
    }
}

/** [AudioPlatform] over `AudioManager`: `setCommunicationDevice` on API 31+, speakerphone/SCO calls before. */
@Suppress("TooManyFunctions")
class AndroidAudioPlatform(context: Context) : AudioPlatform {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var focus: AudioFocusRequest? = null

    override fun devices(): List<RouteDevice> {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return infos.map { RouteDevice(it.id, it.type) }
    }

    override fun enter() {
        savedMode = audio.mode
        @Suppress("DEPRECATION")
        savedSpeaker = audio.isSpeakerphoneOn
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    override fun apply(route: Route) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyModern(route)
        } else {
            applyLegacy(route)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyModern(route: Route) {
        val available = audio.availableCommunicationDevices
        val wanted = when (route) {
            Route.Speaker -> available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            is Route.Wired -> available.firstOrNull { it.id == route.device.id }
            is Route.Bluetooth -> available.firstOrNull { it.id == route.device.id }
        }
        if (wanted == null || !audio.setCommunicationDevice(wanted)) audio.clearCommunicationDevice()
    }

    @Suppress("DEPRECATION")
    private fun applyLegacy(route: Route) {
        when (route) {
            Route.Speaker -> {
                audio.stopBluetoothSco()
                audio.isBluetoothScoOn = false
                audio.isSpeakerphoneOn = true
            }

            is Route.Wired -> {
                audio.stopBluetoothSco()
                audio.isBluetoothScoOn = false
                audio.isSpeakerphoneOn = false
            }

            is Route.Bluetooth -> {
                audio.isSpeakerphoneOn = false
                // A hearing aid (ASHA) is not a SCO device: leave it to the system's own routing.
                if (route.device.type != RoutePolicy.HEARING_AID) {
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                }
            }
        }
    }

    override fun restore() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            with(audio) {
                stopBluetoothSco()
                isBluetoothScoOn = false
                isSpeakerphoneOn = savedSpeaker
            }
        }
        audio.mode = savedMode
    }

    override fun watchDevices(changed: () -> Unit): AutoCloseable {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = changed()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = changed()
        }
        audio.registerAudioDeviceCallback(callback, handler)
        return AutoCloseable { audio.unregisterAudioDeviceCallback(callback) }
    }

    override fun requestFocus(lost: () -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        lost()
                    }
                },
                handler
            )
            .build()
        focus = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
