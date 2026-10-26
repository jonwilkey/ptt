package com.example.ptt.push

import java.util.concurrent.ConcurrentHashMap

/**
 * Session keys of pages this phone received, by page id. Memory only: a key is never written to Room or
 * DataStore (spec, Security), and it is cleared and zeroed when its page ends. The live audio session
 * (milestone 4) reads it from here.
 */
class SessionKeys {
    private val keys = ConcurrentHashMap<String, ByteArray>()

    fun put(pageId: String, key: ByteArray) {
        keys.put(pageId, key)?.fill(0)
    }

    fun get(pageId: String): ByteArray? = keys[pageId]

    fun has(pageId: String): Boolean = keys.containsKey(pageId)

    fun clear(pageId: String) {
        keys.remove(pageId)?.fill(0)
    }

    fun clearAll() {
        keys.keys.toList().forEach(::clear)
    }
}
