package com.example.ptt.outbox

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
import com.example.ptt.data.OutboxKind
import com.example.ptt.data.PttDatabase
import com.example.ptt.data.TestDb
import com.example.ptt.net.ApiResult
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutboxWorkerTest {
    private lateinit var context: Context
    private lateinit var db: PttDatabase
    private val api = FakePttApi()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDb.inMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(kind: OutboxKind, attempts: Int = 0) {
        val row = com.example.ptt.data.OutboxEntity("r1", kind, PAGE, "", attempts, 0, System.currentTimeMillis())
        db.outboxDao().insert(row)
    }

    private fun outbox(scheduler: OutboxScheduler = OutboxScheduler { }) = Outbox(
        db.outboxDao(),
        api,
        Json,
        Clock.systemUTC(),
        scheduler,
        scope,
        Backoff()
    )

    private fun worker(outbox: Outbox) = TestListenableWorkerBuilder<OutboxWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = OutboxWorker(appContext, workerParameters, outbox)
        })
        .build()

    @Test
    fun workerSendsPersistedRowsAfterProcessDeath() = runBlocking {
        // A row left behind by an earlier process (no in-process loop is running).
        seed(OutboxKind.ACKNOWLEDGE, 3)
        val result = worker(outbox()).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("acknowledge:$PAGE"), api.calls)
        assertTrue(db.outboxDao().all().isEmpty())
    }

    @Test
    fun workerAsksForARetryWhileRowsRemain() = runBlocking {
        api.actionResults += ApiResult.Retryable(503)
        seed(OutboxKind.CANCEL, 0)
        val result = worker(outbox()).doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, db.outboxDao().all().size)
    }

    @Test
    fun workerSucceedsAndKeepsRowsWhenNotEnrolled() = runBlocking {
        api.actionResults += ApiResult.NotEnrolled(com.example.ptt.net.NotEnrolledReason.NOT_ENROLLED)
        seed(OutboxKind.CANCEL, 0)
        assertEquals(ListenableWorker.Result.success(), worker(outbox()).doWork())
        assertEquals(1, db.outboxDao().all().size)
    }

    @Test
    fun workerRetriesWhenThePassThrows() = runBlocking {
        api.failNext = IllegalStateException("db")
        seed(OutboxKind.CANCEL, 0)
        assertEquals(ListenableWorker.Result.retry(), worker(outbox()).doWork())
        assertEquals(1, db.outboxDao().all().size)
    }

    @Test
    fun schedulerEnqueuesOneExpeditedNetworkConstrainedJob() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val scheduler = OutboxWorker.WorkManagerScheduler(context)
        scheduler.schedule()
        scheduler.schedule()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(OutboxWorker.UNIQUE_NAME).get()
        // The second request is appended behind the first, so a row inserted while a run finishes is not stranded.
        assertEquals(2, infos.size)
        for (info in infos) {
            assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
            assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED)
        }
    }

    @Test
    fun enqueueRequestsTheWorkerRetryPath() = runBlocking {
        var scheduled = 0
        outbox { scheduled++ }.enqueue(OutboxAction.Cancel(PAGE))
        assertEquals(1, scheduled)
    }

    private companion object {
        const val PAGE = "5f0c2a9e-3b1d-4c7a-9e21-6d8b0f4a7c13"
    }
}
