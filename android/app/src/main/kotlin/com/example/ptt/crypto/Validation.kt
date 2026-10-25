package com.example.ptt.crypto

/** The value rules of docs/protocol.md sections 1 and 4, identical to the server's `wire` package. */
internal object Validation {
    const val UUID_LENGTH = 36
    const val KEY_BYTES = 32
    const val MAX_NAME_CODE_POINTS = 64
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** A canonical lowercase UUID (36 characters). */
    fun isUuid(s: String): Boolean = s.length == UUID_LENGTH && uuid.matches(s)

    fun requireUuid(s: String, what: String) {
        ensure(isUuid(s)) { "$what is not a canonical lowercase UUID" }
    }

    /**
     * 1 to [MAX_NAME_CODE_POINTS] code points, no control characters (Unicode Cc), and some
     * non-space content, where "space" is Unicode White_Space (Go's `unicode.IsSpace`). The JSON
     * reader has already guaranteed the string is valid UTF-16.
     */
    fun isValidText(s: String): Boolean {
        val codePoints = s.codePointCount(0, s.length)
        var hasContent = false
        var controls = false
        s.codePoints().forEach { cp ->
            if (Character.getType(cp) == Character.CONTROL.toInt()) controls = true
            if (!isSpace(cp)) hasContent = true
        }
        return codePoints in 1..MAX_NAME_CODE_POINTS && !controls && hasContent
    }

    fun requireText(s: String, what: String) {
        ensure(isValidText(s)) { "$what is not valid text" }
    }

    /** Unicode White_Space: Go's `unicode.IsSpace`, which the server's `ValidText` uses. */
    private val spaces: Set<Int> =
        (" \t\n\u000B\u000C\r\u0085\u00A0\u1680\u2028\u2029\u202F\u205F\u3000" + ('\u2000'..'\u200A').joinToString(""))
            .map { it.code }.toSet()

    private fun isSpace(cp: Int): Boolean = cp in spaces
}
