package com.example.ptt.push

import java.util.concurrent.ConcurrentHashMap

/**
 * Session keys of pages this phone received, by page id. Memory only: a key is never written to Room or
 * DataStore (spec, Security), and it is cleared and zeroed when its page ends. The live audio session
 * (milestone 4) reads it from here.
 */
class SessionKeys {
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val peers = ConcurrentHashMap<String, Set<String>>()

    /**
     * The device ids that may be the other end of [pageId]'s live session, from the signed envelope: for a page
     * this phone sent, the recipient's devices that hold a wrapped key; for a page it received, the sender device.
     */
    fun putPeers(pageId: String, devices: Set<String>) {
        peers[pageId] = devices.toSet()
    }

    fun peers(pageId: String): Set<String> = peers[pageId].orEmpty()

    fun put(pageId: String, key: ByteArray) {
        keys.put(pageId, key)?.fill(0)
    }

    /** The live array, which [clear] zeroes in place. Audio code must use [copy] instead. */
    fun get(pageId: String): ByteArray? = keys[pageId]

    /**
     * An independent copy of the key, made atomically with respect to [clear] (both run under the map's
     * per-key lock), so it is never a zeroed or partly zeroed key. Use this to start an audio session.
     */
    fun copy(pageId: String): ByteArray? {
        var out: ByteArray? = null
        keys.computeIfPresent(pageId) { _, v ->
            out = v.copyOf()
            v
        }
        return out
    }

    fun has(pageId: String): Boolean = keys.containsKey(pageId)

    fun clear(pageId: String) {
        peers.remove(pageId)
        keys.computeIfPresent(pageId) { _, v ->
            v.fill(0)
            null
        }
    }

    fun clearAll() {
        keys.keys.toList().forEach(::clear)
    }
}
