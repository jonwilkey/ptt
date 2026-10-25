package com.example.ptt

import android.app.Application
import com.example.ptt.push.FirebaseInitializer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PttApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseInitializer.initialize(this)
    }
}
