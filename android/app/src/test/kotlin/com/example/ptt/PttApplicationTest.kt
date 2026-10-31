package com.example.ptt

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PttApplicationTest {
    @Test
    fun onCreateStartsTheObserversThroughTheSeam() {
        val app = ApplicationProvider.getApplicationContext<TestPttApplication>()
        assertTrue(app.observersRequested)
    }

    @Test
    fun terminatingTheTestApplicationCancelsAndEndsWhatRunsOnTheAppScope() {
        val app = ApplicationProvider.getApplicationContext<TestPttApplication>()
        val child: Job = app.appScope.launch { awaitCancellation() }
        assertTrue(child.isActive)
        app.onTerminate()
        assertTrue(child.isCancelled)
        assertTrue(child.isCompleted)
        assertFalse(app.appScope.coroutineContext[Job]!!.isActive)
    }
}
