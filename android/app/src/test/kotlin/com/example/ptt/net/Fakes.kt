package com.example.ptt.net

/** In-memory token store for JVM tests (Robolectric has no AndroidKeyStore). */
class InMemoryDeviceTokenStore(
    private var token: String? = null,
    private var deviceId: String? = null,
    private val failWith: DeviceTokenUnavailable? = null
) : DeviceTokenStore {
    override fun save(deviceId: String, token: String) {
        failWith?.let { throw it }
        this.deviceId = deviceId
        this.token = token
    }

    override fun token(): String? {
        failWith?.let { throw it }
        return token
    }

    override fun deviceId(): String? = deviceId

    override fun clear() {
        token = null
        deviceId = null
    }
}

class FakeEnrollmentState(var url: String? = null, var needsReenroll: Boolean = false) : EnrollmentState {
    override suspend fun serverUrl(): String? = url

    override suspend fun needsReenrollment(): Boolean = needsReenroll

    override fun markNeedsReenrollment() {
        needsReenroll = true
    }
}
