package com.example.ptt.net

/**
 * What the network layer needs to know about enrollment. Implemented by the settings repository
 * (`data`); tests supply a fake.
 */
interface EnrollmentState {
    /** The enrolled server's base URL (no trailing slash), or null when not enrolled. */
    suspend fun serverUrl(): String?

    /** True after the server answered 401 to this device's token. Cleared by a new enrollment. */
    suspend fun needsReenrollment(): Boolean

    /**
     * Records a 401. Blocking, because it is called from an OkHttp interceptor thread. It does not
     * wipe keys or the token: that is the re-enrollment flow's job.
     */
    fun markNeedsReenrollment()
}
