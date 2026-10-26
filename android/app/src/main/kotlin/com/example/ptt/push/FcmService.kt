package com.example.ptt.push

import com.example.ptt.setup.DeviceRegistration
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * Receives FCM data messages. Firebase calls this on a background thread and keeps the process alive only
 * until it returns, so the work (including a page's `delivered` call) is done before returning. When
 * Firebase is not configured this service is never started.
 */
@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {
    @Inject
    lateinit var dispatcher: PushDispatcher

    @Inject
    lateinit var registration: DeviceRegistration

    override fun onMessageReceived(message: RemoteMessage) {
        runBlocking { dispatcher.dispatch(message.data) }
    }

    // The Kotlin compiler flags this override as deprecated in this Firebase version; it is still the hook
    // Firebase calls for a new token.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        // A failure is retried by the next registration sync (the token's hash was not recorded as sent).
        runBlocking { registration.onNewToken(token) }
    }
}
