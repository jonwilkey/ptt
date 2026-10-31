package com.example.ptt

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The application Robolectric builds for every unit test (robolectric.properties). It is the real Hilt application
 * with two differences, because the app scope never ends on its own: the coroutines on it would outlive the test,
 * crash once Robolectric resets the framework statics, and have kotlinx-coroutines-test blame whichever test runs
 * next.
 *
 * - The app-wide observers are not started (a test that needs one builds and starts it itself).
 * - Robolectric calls [onTerminate] when it tears the application down: the app scope (which the outbox, the alert
 *   engine and the like also run on) is cancelled and joined then.
 */
class TestPttApplication : PttApplication() {
    /** Whether `onCreate` asked for the observers (they are not started). */
    var observersRequested = false
        private set

    override fun startObservers() {
        observersRequested = true
    }

    override fun onTerminate() {
        val job = appScope.coroutineContext.job
        runBlocking { withTimeoutOrNull(2.seconds) { job.cancelAndJoin() } }
        super.onTerminate()
        check(job.isCompleted) { "app-scope coroutines did not finish: " + job.children.toList() }
    }
}
