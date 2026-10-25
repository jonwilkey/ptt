package com.example.ptt.crypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A parsed JSON value. Numbers are always integers that fit a Long; there is no null. */
sealed interface JsonValue {
    class Obj(val members: Map<String, JsonValue>) : JsonValue
    class Arr(val items: List<JsonValue>) : JsonValue
    class Str(val value: String) : JsonValue
    class Num(val value: Long) : JsonValue
    class Bool(val value: Boolean) : JsonValue
}

/**
 * The strict JSON reader of docs/protocol.md section 3, written out by hand because neither
 * kotlinx.serialization nor org.json reject duplicate keys or invalid UTF-8 reliably. It rejects:
 * invalid UTF-8; duplicate keys (compared after unescaping, so `"a"` and `"a"` collide);
 * `null` anywhere; numbers that are not plain integers in the Long range; raw control characters in
 * strings; unpaired surrogate escapes; trailing data; and nesting beyond [MAX_DEPTH]. Key names
 * are case-sensitive; use [JsonValue.Obj.exactly] to also reject unknown and missing keys.
 */
object StrictJson {
    private const val MAX_DEPTH = 16

    fun parse(data: ByteArray): JsonValue {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            protocolError("JSON: invalid UTF-8")
        }
        return Reader(text).document()
    }

    fun parse(text: String): JsonValue = parse(text.toByteArray(Charsets.UTF_8))

    // One small method per grammar production reads better than fewer, larger ones.
    @Suppress("TooManyFunctions")
    private class Reader(private val s: String) {
        private var pos = 0

        fun document(): JsonValue {
            skipWhitespace()
            val value = value(0)
            skipWhitespace()
            ensure(pos == s.length) { "JSON: trailing data" }
            return value
        }

        private fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\n\r") pos++
        }

        private fun peek(): Char {
            ensure(pos < s.length) { "JSON: unexpected end" }
            return s[pos]
        }

        private fun expect(c: Char) {
            ensure(peek() == c) { "JSON: expected '$c'" }
            pos++
        }

        private fun value(depth: Int): JsonValue {
            ensure(depth <= MAX_DEPTH) { "JSON: nested too deeply" }
            return when (peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                '-', in '0'..'9' -> number()
                else -> protocolError("JSON: unexpected character (null is not allowed)")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            ensure(s.startsWith(word, pos)) { "JSON: bad literal" }
            pos += word.length
            return value
        }

        private fun number(): JsonValue {
            val start = pos
            if (s[pos] == '-') pos++
            ensure(pos < s.length && s[pos] in '0'..'9') { "JSON: bad number" }
            if (s[pos] == '0') pos++ else while (pos < s.length && s[pos] in '0'..'9') pos++
            ensure(pos == s.length || s[pos] !in ".eE") { "JSON: only integers are allowed" }
            val parsed = s.substring(start, pos).toLongOrNull()
            ensure(parsed != null) { "JSON: integer out of range" }
            return JsonValue.Num(parsed)
        }

        private fun arr(depth: Int): JsonValue {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
            } else {
                do {
                    skipWhitespace()
                    items += value(depth + 1)
                    skipWhitespace()
                } while (separator(']'))
            }
            return JsonValue.Arr(items)
        }

        private fun obj(depth: Int): JsonValue {
            expect('{')
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
            } else {
                do {
                    skipWhitespace()
                    ensure(peek() == '"') { "JSON: object key must be a string" }
                    val key = string()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    ensure(key !in members) { "JSON: duplicate key" }
                    members[key] = value(depth + 1)
                    skipWhitespace()
                } while (separator('}'))
            }
            return JsonValue.Obj(members)
        }

        /** Consumes ',' (true: another element follows) or [close] (false: the container ended). */
        private fun separator(close: Char): Boolean {
            val c = peek()
            ensure(c == ',' || c == close) { "JSON: expected ',' or '$close'" }
            pos++
            return c == ','
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = peek()
                pos++
                when {
                    c == '"' -> break
                    c == '\\' -> out.append(escape())
                    c < ' ' -> protocolError("JSON: raw control character in string")
                    else -> out.append(c)
                }
            }
            requireNoLoneSurrogates(out)
            return out.toString()
        }

        private fun escape(): Char {
            val c = peek()
            pos++
            return when (c) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicodeEscape()
                else -> protocolError("JSON: bad escape")
            }
        }

        private fun unicodeEscape(): Char {
            ensure(pos + HEX_DIGITS <= s.length) { "JSON: bad unicode escape" }
            var code = 0
            repeat(HEX_DIGITS) {
                val digit = Character.digit(s[pos++], HEX_RADIX)
                ensure(digit >= 0 && s[pos - 1].code < ASCII) { "JSON: bad unicode escape" }
                code = code * HEX_RADIX + digit
            }
            return code.toChar()
        }

        private fun requireNoLoneSurrogates(text: CharSequence) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> i++
                    Character.isSurrogate(c) -> protocolError("JSON: unpaired surrogate")
                }
                i++
            }
        }

        private companion object {
            const val HEX_DIGITS = 4
            const val HEX_RADIX = 16
            const val ASCII = 128
        }
    }
}

/** The members of an object, requiring exactly [keys]: no unknown key and none missing. */
fun JsonValue.Obj.exactly(vararg keys: String): JsonValue.Obj = exactly(keys.toSet())

/** Like the vararg form, for a prepared key set. */
fun JsonValue.Obj.exactly(keys: Set<String>): JsonValue.Obj {
    ensure(members.keys == keys) { "JSON: object does not have exactly the documented keys" }
    return this
}

internal fun JsonValue.asObject(what: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: protocolError("$what must be an object")

internal fun JsonValue.Obj.string(key: String): String =
    (members[key] as? JsonValue.Str)?.value ?: protocolError("$key must be a string")

internal fun JsonValue.Obj.number(key: String): Long =
    (members[key] as? JsonValue.Num)?.value ?: protocolError("$key must be an integer")

internal fun JsonValue.Obj.bool(key: String): Boolean =
    (members[key] as? JsonValue.Bool)?.value ?: protocolError("$key must be a boolean")

internal fun JsonValue.Obj.array(key: String): List<JsonValue> =
    (members[key] as? JsonValue.Arr)?.items ?: protocolError("$key must be an array")

internal fun JsonValue.Obj.obj(key: String): JsonValue.Obj =
    members[key] as? JsonValue.Obj ?: protocolError("$key must be an object")

/** Minimal JSON text writer for the payloads this app signs. */
internal object JsonText {
    fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
