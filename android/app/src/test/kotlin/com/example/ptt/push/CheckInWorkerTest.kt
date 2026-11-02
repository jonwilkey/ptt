package com.example.ptt.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.ptt.setup.AdminHarness
import com.example.ptt.setup.AndroidSystemProbe
import com.example.ptt.setup.SetupChecklist
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CheckInWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var h: AdminHarness
    private val f get() = h.f
    private val put = "/v1/devices/me"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        h = AdminHarness(tmp.newFolder())
        h.enrollAsBootstrapAdmin()
        f.fake.reply("PUT", put, 204)
        f.fcmToken = "fcm-one"
        f.checklistMap = mapOf("microphone" to true, "battery" to false)
    }

    @After
    fun tearDown() = f.close()

    private fun worker() = TestListenableWorkerBuilder<CheckInWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = CheckInWorker(appContext, workerParameters, f.registration)
        })
        .build()

    private fun lastBody() = Json.parseToJsonElement(f.fake.bodies("PUT", put).last()).jsonObject

    @Test(timeout = 60_000)
    fun theReplySendsEveryFieldEvenWhenNothingChanged() = runBlocking {
        assertTrue(f.registration.sync())
        val before = f.fake.count("PUT", put)
        assertTrue(f.registration.sync())
        assertEquals(before, f.fake.count("PUT", put))

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(before + 1, f.fake.count("PUT", put))
        assertEquals(setOf("app_version", "checklist", "fcm_token", "device_name"), lastBody().keys)
        assertEquals(
            """{"microphone":true,"battery":false}""",
            lastBody().getValue("checklist").toString()
        )
    }

    @Test(timeout = 60_000)
    fun theCacheIsUpdatedSoNormalSyncsStayQuietAfterwards() = runBlocking {
        f.fcmToken = "fcm-two"
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val sent = f.fake.count("PUT", put)
        assertTrue(f.registration.sync())
        assertEquals(sent, f.fake.count("PUT", put))
    }

    @Test(timeout = 60_000)
    fun theChecklistIsEvaluatedInsideTheWorkerFromAPlainContext() = runBlocking {
        f.checklistSource = SetupChecklist(AndroidSystemProbe(context))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        val checklist = lastBody().getValue("checklist").jsonObject
        assertTrue(checklist.keys.containsAll(listOf("microphone", "notifications")))
    }

    @Test(timeout = 60_000)
    fun aServerErrorIsRetried() = runBlocking {
        f.fake.reply("PUT", put, 503, "")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test(timeout = 60_000)
    fun anUnreachableServerIsRetried() = runBlocking {
        f.web.close()
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test(timeout = 60_000)
    fun aClientErrorFailsWithoutRetry() = runBlocking {
        f.fake.reply("PUT", put, 400, """{"error":"invalid_request"}""")
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
    }

    @Test(timeout = 60_000)
    fun theSchedulerKeepsOneNetworkConstrainedUniqueJob() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = CheckInWorker.WorkManagerScheduler(context)
        scheduler.schedule()
        scheduler.schedule()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CheckInWorker.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }
}
