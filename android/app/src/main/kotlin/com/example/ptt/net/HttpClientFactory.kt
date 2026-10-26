package com.example.ptt.net

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.TlsVersion

/** Builds the app's single OkHttp client. */
object HttpClientFactory {
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_WRITE_TIMEOUT_S = 20L
    private const val CALL_TIMEOUT_S = 30L
    private const val TAG = "PttHttp"

    /** TLS 1.3 only (spec: "TLS 1.3 only"), with OkHttp's modern cipher-suite list. */
    val tls13Only: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_3)
        .build()

    /**
     * Release: TLS 1.3 only. Debug adds cleartext, which the debug network security config limits to
     * 10.0.2.2 and localhost.
     */
    fun connectionSpecs(debug: Boolean): List<ConnectionSpec> =
        if (debug) listOf(tls13Only, ConnectionSpec.CLEARTEXT) else listOf(tls13Only)

    fun create(auth: AuthInterceptor, debug: Boolean, log: (String) -> Unit = { Log.d(TAG, it) }): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionSpecs(connectionSpecs(debug))
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(auth)
        if (debug) builder.addInterceptor(RedactingLogInterceptor(log))
        return builder.build()
    }
}

/**
 * Debug-only: logs "METHOD status" per call and nothing else. No URL (ids), headers (the
 * Authorization token) or bodies (envelopes) ever reach the log.
 */
class RedactingLogInterceptor(private val log: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request).also { log("${request.method} ${it.code}") }
        } catch (e: java.io.IOException) {
            log("${request.method} failed: ${e.javaClass.simpleName}")
            throw e
        }
    }
}
