package com.example.ptt.crypto

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * A protocol violation: malformed encoding, a rejected value, a failed check. The message names the
 * rule that failed and never contains key material, tokens, payloads or other secrets.
 */
class ProtocolException(message: String) : Exception(message)

internal fun protocolError(message: String): Nothing = throw ProtocolException(message)

@OptIn(ExperimentalContracts::class)
internal inline fun ensure(condition: Boolean, message: () -> String) {
    contract { returns() implies condition }
    if (!condition) protocolError(message())
}
