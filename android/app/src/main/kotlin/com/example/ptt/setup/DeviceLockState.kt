package com.example.ptt.setup

import android.app.KeyguardManager
import android.content.Context

/**
 * Whether the phone is showing its lock screen. Admin actions (adding or removing a device) are
 * refused while it is: the app never shows them over the lock screen, and this is the backstop.
 */
fun interface DeviceLockState {
    fun isLocked(): Boolean
}

class KeyguardLockState(private val context: Context) : DeviceLockState {
    override fun isLocked(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked ?: true
}
