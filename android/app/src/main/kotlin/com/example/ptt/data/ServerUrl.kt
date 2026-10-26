package com.example.ptt.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validation of the server URL that enrollment stores (it comes from a scanned QR code). */
object ServerUrl {
    private val debugCleartextHosts = setOf("10.0.2.2", "localhost")

    /**
     * Returns the canonical base URL (scheme and authority, no trailing slash) or null if it is not
     * acceptable: HTTPS only, with no credentials, path, query or fragment. A debug build also
     * accepts http for 10.0.2.2 and localhost, matching its network security config.
     */
    fun normalize(raw: String, debug: Boolean): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val cleartextOk = debug && url.host in debugCleartextHosts
        val schemeOk = url.isHttps || cleartextOk
        val bare = url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" &&
            url.query == null && url.fragment == null
        return if (schemeOk && bare) url.toString().trimEnd('/') else null
    }
}
