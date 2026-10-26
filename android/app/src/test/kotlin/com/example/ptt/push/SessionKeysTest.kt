package com.example.ptt.push

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeysTest {
    @Test
    fun keysAreHeldPerPageAndZeroedWhenCleared() {
        val keys = SessionKeys()
        val a = ByteArray(32) { 5 }
        keys.put("a", a)
        keys.put("b", ByteArray(32) { 6 })
        assertTrue(keys.has("a"))
        assertArrayEquals(ByteArray(32) { 6 }, keys.get("b"))
        keys.clear("a")
        assertFalse(keys.has("a"))
        assertNull(keys.get("a"))
        assertArrayEquals(ByteArray(32), a)
        keys.clearAll()
        assertFalse(keys.has("b"))
    }

    @Test
    fun replacingAKeyZeroesTheOldOne() {
        val keys = SessionKeys()
        val old = ByteArray(32) { 1 }
        keys.put("a", old)
        keys.put("a", ByteArray(32) { 2 })
        assertArrayEquals(ByteArray(32), old)
        assertArrayEquals(ByteArray(32) { 2 }, keys.get("a"))
    }
}
