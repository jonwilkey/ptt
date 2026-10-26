package com.example.ptt.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.ptt.crypto.EnrollmentQr
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddDeviceViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f
    private val store = ViewModelStore()
    private val phone = NewPhone()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = AdminHarness(tmp.newFolder())
        h.enrollAsBootstrapAdmin()
        h.serveCodeCreated()
    }

    @After
    fun tearDown() {
        store.clear()
        f.close()
        Dispatchers.resetMain()
    }

    private fun newVm(): AddDeviceViewModel {
        val flow = h.addDevice
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                AddDeviceViewModel(flow, f.rosters, f.tokens) as T
        }
        return ViewModelProvider(store, factory)[AddDeviceViewModel::class.java]
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun secretOf(vm: AddDeviceViewModel): ByteArray =
        EnrollmentQr.parse((vm.ui.value as AddDeviceUi.ShowingQr).qrText, allowDevHttp = true).secret!!

    /** Starts an enrollment and serves the "used" answer for the phone, MAC'd with the QR's real secret. */
    private fun startAndServeUsed(vm: AddDeviceViewModel) {
        vm.start(AddTarget.NewPerson("Alex", false))
        waitFor("QR") { vm.ui.value is AddDeviceUi.ShowingQr }
        h.serveCodeStatus(phone.usedJson(phone.mac(secretOf(vm), h.code)))
    }

    @Test
    fun aStopOfTheScreenOnlyPausesPollingAndTheEnrollmentResumes() = runBlocking {
        val vm = newVm()
        vm.start(AddTarget.NewPerson("Alex", false))
        waitFor("QR") { vm.ui.value is AddDeviceUi.ShowingQr }
        h.serveCodeStatus("""{"status":"pending"}""")
        f.fake.reply("PUT", "/v1/roster", 204)

        // STOP: repeatOnLifecycle cancels the loop. Nothing abandons.
        val first = launch(Dispatchers.Default) { vm.pollLoop(10) }
        waitFor("a poll") { f.fake.count("GET", "/v1/enrollment-codes/$CODE_ID") > 0 }
        first.cancel()
        first.join()
        assertTrue(vm.ui.value is AddDeviceUi.ShowingQr)
        assertTrue("secret intact", secretOf(vm).any { it != 0.toByte() })
        val polls = f.fake.count("GET", "/v1/enrollment-codes/$CODE_ID")
        delay(100)
        assertEquals("paused while stopped", polls, f.fake.count("GET", "/v1/enrollment-codes/$CODE_ID"))

        // START again: the phone has meanwhile enrolled; polling resumes and the roster is signed.
        h.serveCodeStatus(phone.usedJson(phone.mac(secretOf(vm), h.code)))
        val second = launch(Dispatchers.Default) { vm.pollLoop(10) }
        waitFor("done") { vm.ui.value is AddDeviceUi.Done }
        second.cancel()
        assertEquals(1, h.puts())
    }

    @Test
    fun explicitAbandonAndOnClearedEndTheEnrollment() = runBlocking {
        val vm = newVm()
        vm.start(AddTarget.NewPerson("Alex", false))
        waitFor("QR") { vm.ui.value is AddDeviceUi.ShowingQr }
        vm.abandon()
        assertEquals(AddDeviceUi.Form, vm.ui.value)
        val polls = f.fake.count("GET", "/v1/enrollment-codes/$CODE_ID")
        vm.pollOnce()
        assertEquals("nothing left to poll", polls, f.fake.count("GET", "/v1/enrollment-codes/$CODE_ID"))

        h.serveCodeCreated()
        vm.start(AddTarget.NewPerson("Alex", false))
        waitFor("QR again") { vm.ui.value is AddDeviceUi.ShowingQr }
        store.clear()
        assertEquals(AddDeviceUi.Form, vm.ui.value)
    }

    @Test
    fun aFailedSigningCanBeRetriedAndAnUnlockedPhoneRetriesTooButTheEnrollmentIsNotLost() = runBlocking {
        val vm = newVm()
        startAndServeUsed(vm)
        f.fake.reply("PUT", "/v1/roster", 503)
        vm.pollOnce()
        waitFor("failed") { vm.ui.value is AddDeviceUi.Failed }
        val failed = vm.ui.value as AddDeviceUi.Failed
        assertEquals(AddDeviceError.NETWORK, failed.error)
        assertTrue(failed.retry)

        // Locked phone: refused locally, still retryable.
        h.locked = true
        vm.retrySigning()
        waitFor("locked") { (vm.ui.value as? AddDeviceUi.Failed)?.error == AddDeviceError.LOCKED }
        assertTrue((vm.ui.value as AddDeviceUi.Failed).retry)
        assertEquals("no PUT while locked", 1, h.puts())

        h.locked = false
        f.fake.reply("PUT", "/v1/roster", 204)
        vm.retrySigning()
        waitFor("done") { vm.ui.value is AddDeviceUi.Done }
        assertEquals(2L, f.rosters.current()!!.version)
    }

    @Test
    fun abandoningDuringSigningLetsTheAttemptFinishInsteadOfCancellingThePut() = runBlocking {
        val vm = newVm()
        startAndServeUsed(vm)
        val inPut = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.fake.on("PUT", "/v1/roster") {
            inPut.countDown()
            release.await(10, TimeUnit.SECONDS)
            FakeServer.response(204)
        }
        vm.pollOnce()
        assertTrue(inPut.await(10, TimeUnit.SECONDS))
        assertEquals(AddDeviceUi.Signing, vm.ui.value)

        vm.abandon()
        assertEquals("still signing", AddDeviceUi.Signing, vm.ui.value)
        release.countDown()
        waitFor("finished") { vm.ui.value !is AddDeviceUi.Signing }

        assertTrue(vm.ui.value is AddDeviceUi.Done)
        assertEquals(2L, f.rosters.current()!!.version)
        assertEquals(1, h.puts())
    }
}
