package com.example.ptt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePlatform : AudioPlatform {
    var devices: List<RouteDevice> = listOf(SPEAKER, EARPIECE)
    val calls = ArrayList<String>()
    var changed: (() -> Unit)? = null
    var focusLost: (() -> Unit)? = null
    var focusHeld = false

    override fun devices() = devices

    override fun enter() {
        calls += "enter"
    }

    override fun apply(route: Route) {
        calls += "apply $route"
    }

    override fun restore() {
        calls += "restore"
    }

    override fun watchDevices(changed: () -> Unit): AutoCloseable {
        this.changed = changed
        return AutoCloseable {
            calls += "unwatch"
            this.changed = null
        }
    }

    override fun requestFocus(lost: () -> Unit): Boolean {
        focusLost = lost
        focusHeld = true
        return true
    }

    override fun abandonFocus() {
        focusHeld = false
    }

    fun connect(device: RouteDevice) {
        devices = devices + device
        changed?.invoke()
    }

    fun disconnect(device: RouteDevice) {
        devices = devices - device
        changed?.invoke()
    }

    companion object {
        val SPEAKER = RouteDevice(1, 2)
        val EARPIECE = RouteDevice(2, 1)
        val WIRED = RouteDevice(3, 3)
        val USB = RouteDevice(4, 22)
        val SCO = RouteDevice(5, 7)
        val BLE = RouteDevice(6, 26)
    }
}

class AudioRoutingTest {
    private val platform = FakePlatform()
    private val routing = AudioRouting(platform)

    @Test(timeout = 60_000)
    fun speakerIsTheDefaultEvenWithAnEarpiece() {
        assertEquals(Route.Speaker, RoutePolicy.choose(platform.devices))
        assertEquals(Route.Speaker, RoutePolicy.choose(emptyList()))
    }

    @Test(timeout = 60_000)
    fun wiredBeatsBluetoothAndBleBeatsScoOtherwiseBluetoothBeatsSpeaker() {
        val base = platform.devices
        assertEquals(Route.Wired(FakePlatform.WIRED), RoutePolicy.choose(base + FakePlatform.SCO + FakePlatform.WIRED))
        assertEquals(Route.Wired(FakePlatform.USB), RoutePolicy.choose(base + FakePlatform.USB))
        assertEquals(Route.Bluetooth(FakePlatform.BLE), RoutePolicy.choose(base + FakePlatform.SCO + FakePlatform.BLE))
        assertEquals(Route.Bluetooth(FakePlatform.SCO), RoutePolicy.choose(base + FakePlatform.SCO))
    }

    @Test(timeout = 60_000)
    fun startEntersCommunicationModeTakesFocusAndRoutesToTheSpeaker() {
        routing.start()
        assertEquals(listOf("enter", "apply Speaker"), platform.calls)
        assertTrue(platform.focusHeld)
        assertEquals(Route.Speaker, routing.route)
    }

    @Test(timeout = 60_000)
    fun aDeviceConnectingAndDisconnectingMidSessionSwitchesTheRoute() {
        routing.start()
        platform.connect(FakePlatform.SCO)
        assertEquals(Route.Bluetooth(FakePlatform.SCO), routing.route)
        platform.connect(FakePlatform.WIRED)
        assertEquals(Route.Wired(FakePlatform.WIRED), routing.route)
        platform.disconnect(FakePlatform.WIRED)
        assertEquals(Route.Bluetooth(FakePlatform.SCO), routing.route)
        platform.disconnect(FakePlatform.SCO)
        assertEquals(Route.Speaker, routing.route)
        // An unrelated change does not re-apply the same route.
        val before = platform.calls.size
        platform.connect(RouteDevice(9, 99))
        assertEquals(before, platform.calls.size)
    }

    @Test(timeout = 60_000)
    fun stopRestoresEverythingAndIgnoresLaterDeviceChanges() {
        routing.start()
        routing.stop()
        assertEquals("restore", platform.calls.last { it == "restore" })
        assertFalse(platform.focusHeld)
        assertNull(routing.route)
        assertNull(platform.changed)
        routing.refresh()
        assertEquals(1, platform.calls.count { it == "restore" })
    }

    @Test(timeout = 60_000)
    fun startAndStopAreIdempotent() {
        routing.start()
        routing.start()
        assertEquals(1, platform.calls.count { it == "enter" })
        routing.stop()
        routing.stop()
        assertEquals(1, platform.calls.count { it == "restore" })
    }

    @Test(timeout = 60_000)
    fun closeIsTerminalAndStartAfterItDoesNothing() {
        routing.start()
        routing.close()
        routing.start()
        assertEquals(1, platform.calls.count { it == "enter" })
        assertEquals(1, platform.calls.count { it == "restore" })
        assertNull(routing.route)
    }

    @Test(timeout = 60_000)
    fun focusIsRequestedAgainAfterALoss() {
        routing.start()
        platform.focusLost?.invoke()
        platform.focusHeld = false
        routing.ensureFocus()
        assertTrue(platform.focusHeld)
    }

    @Test(timeout = 60_000)
    fun focusLossIsReported() {
        var lost = 0
        val r = AudioRouting(platform) { lost++ }
        r.start()
        platform.focusLost?.invoke()
        assertEquals(1, lost)
    }
}
