package com.example.ptt.setup

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** The device's FCM registration token, or null when Firebase is unavailable or does not answer. */
fun interface FcmTokenSource {
    suspend fun token(): String?
}

class FirebaseFcmTokenSource(private val context: Context) : FcmTokenSource {
    override suspend fun token(): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                // Firebase marks the Kotlin property form deprecated while the method has no replacement.
                @Suppress("DEPRECATION")
                val task: Task<String> = FirebaseMessaging.getInstance().token
                task.addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                task.addOnFailureListener { if (cont.isActive) cont.resume(null) }
                task.addOnCanceledListener { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
