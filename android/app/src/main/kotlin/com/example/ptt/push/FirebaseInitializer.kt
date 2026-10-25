package com.example.ptt.push

import android.content.Context
import android.util.Log
import com.example.ptt.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Initialises Firebase from BuildConfig values (no google-services plugin or JSON file). When
 * the values were not supplied at build time (CI, contributors) FCM stays off.
 */
object FirebaseInitializer {
    private const val TAG = "FirebaseInitializer"

    /** Returns true if Firebase is available after the call. */
    fun initialize(context: Context): Boolean {
        val options = buildOptions(
            projectId = BuildConfig.FIREBASE_PROJECT_ID,
            appId = BuildConfig.FIREBASE_APP_ID,
            apiKey = BuildConfig.FIREBASE_API_KEY,
            senderId = BuildConfig.FIREBASE_SENDER_ID
        )
        if (options == null) {
            Log.w(TAG, "Firebase settings are not configured; FCM is unavailable")
            return false
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context, options)
        }
        return true
    }

    /** Null unless all four values are present. */
    fun buildOptions(projectId: String, appId: String, apiKey: String, senderId: String): FirebaseOptions? {
        if (listOf(projectId, appId, apiKey, senderId).any { it.isBlank() }) return null
        return FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setGcmSenderId(senderId)
            .build()
    }
}
