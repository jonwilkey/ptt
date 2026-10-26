package com.example.ptt.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.ptt.crypto.DeviceKeys
import com.example.ptt.setup.SetupFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RosterSyncWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var f: SetupFixture
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        f = SetupFixture(tmp.newFolder())
        runBlocking {
            DeviceKeys.create(f.keyStore)
            f.settings.setEnrolled(f.url)
            f.tokens.save("c3e9a7f1-5d2b-4b84-a6e0-9f1c8d3b2a57", "token")
        }
    }

    @After
    fun tearDown() = f.close()

    private fun worker(attempt: Int = 0) = TestListenableWorkerBuilder<RosterSyncWorker>(context)
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = RosterSyncWorker(appContext, workerParameters, f.sync)
        })
        .build()

    @Test
    fun theWorkerRunsTheRosterSyncAndSucceedsWhenTheServerHasNoRoster() = runBlocking {
        f.fake.reply("GET", "/v1/roster", 404, """{"error":"not_found"}""")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, f.fake.count("GET", "/v1/roster"))
    }

    @Test
    fun anUnreachableServerIsRetriedAFewTimesThenGivenUp() = runBlocking {
        f.fake.reply("GET", "/v1/roster", 503, "")
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(attempt = 5).doWork())
    }

    @Test
    fun theSchedulerEnqueuesExpeditedNetworkConstrainedWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        RosterSyncWorker.WorkManagerScheduler(context).schedule()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RosterSyncWorker.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }
}
