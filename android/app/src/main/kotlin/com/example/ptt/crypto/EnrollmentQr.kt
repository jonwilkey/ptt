package com.example.ptt.crypto

/**
 * The enrollment QR text `pttenroll1:` + b64u(JSON) (docs/protocol.md section 5). Bootstrap QRs have
 * no [secret] and no [admins]; admin QRs have both.
 */
class EnrollmentQr(val url: String, val code: ByteArray, val secret: ByteArray?, val admins: List<TrustedAdmin>) {
    val isBootstrap: Boolean get() = secret == null

    /** The exact QR text. Keys are written in the documented order: url, code, secret, admins. */
    fun encode(): String {
        ensure(code.size == CODE_BYTES) { "enrollment code must be $CODE_BYTES bytes" }
        val json = StringBuilder("""{"url":${JsonText.quote(url)},"code":"${B64u.encode(code)}"""")
        if (secret != null) {
            ensure(secret.size == Validation.KEY_BYTES) { "enrollment secret must be 32 bytes" }
            val list = admins.joinToString(",") { """{"id":"${it.id}","ed25519":"${B64u.encode(it.ed25519)}"}""" }
            json.append(""","secret":"${B64u.encode(secret)}","admins":[$list]""")
        } else {
            ensure(admins.isEmpty()) { "a bootstrap QR has no admins" }
        }
        return PREFIX + B64u.encode(json.append('}').toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "pttenroll1:"
        const val CODE_BYTES = 16

        private val devHttp = Regex("http://(10\\.0\\.2\\.2|localhost)(:[0-9]{1,5})?(/.*)?")

        /**
         * Strictly parses scanned QR text; throws [ProtocolException] unless it is a valid QR. The URL
         * must be `https://`. Only with [allowDevHttp] (pass `BuildConfig.DEBUG`) are the emulator host
         * `http://10.0.2.2` and `http://localhost` also accepted, matching the debug network config.
         */
        fun parse(text: String, allowDevHttp: Boolean = false): EnrollmentQr {
            ensure(text.startsWith(PREFIX)) { "not an enrollment QR" }
            val obj = StrictJson.parse(B64u.decode(text.removePrefix(PREFIX))).asObject("enrollment QR")
            val bootstrap = obj.members.keys == setOf("url", "code")
            val keys = if (bootstrap) setOf("url", "code") else setOf("url", "code", "secret", "admins")
            obj.exactly(keys)
            val url = obj.string("url")
            ensure(isAcceptableUrl(url, allowDevHttp)) { "QR url must be https" }
            val code = B64u.decodeExact(obj.string("code"), CODE_BYTES, "enrollment code")
            if (bootstrap) return EnrollmentQr(url, code, null, emptyList())
            val secret = B64u.decodeExact(obj.string("secret"), Validation.KEY_BYTES, "enrollment secret")
            val admins = obj.array("admins").map { admin ->
                val a = admin.asObject("QR admin").exactly("id", "ed25519")
                Validation.requireUuid(a.string("id"), "QR admin id")
                TrustedAdmin(
                    a.string("id"),
                    B64u.decodeExact(a.string("ed25519"), Validation.KEY_BYTES, "QR admin key")
                )
            }
            ensure(admins.isNotEmpty()) { "an admin QR lists at least one admin" }
            return EnrollmentQr(url, code, secret, admins)
        }

        private fun isAcceptableUrl(url: String, allowDevHttp: Boolean): Boolean =
            (url.startsWith("https://") && url.length > "https://".length && url.none { it <= ' ' }) ||
                (allowDevHttp && devHttp.matches(url))
    }
}
