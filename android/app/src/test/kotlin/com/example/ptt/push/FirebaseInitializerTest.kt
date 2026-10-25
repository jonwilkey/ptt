package com.example.ptt.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirebaseInitializerTest {
    @Test
    fun missingValueMeansNoOptions() {
        assertNull(FirebaseInitializer.buildOptions("", "app", "key", "123"))
        assertNull(FirebaseInitializer.buildOptions("proj", "app", "key", " "))
    }

    @Test
    fun allValuesBuildOptions() {
        val options = FirebaseInitializer.buildOptions("example-ptt", "1:123:android:abc", "key", "123")
        assertNotNull(options)
        assertEquals("example-ptt", options?.projectId)
    }
}
