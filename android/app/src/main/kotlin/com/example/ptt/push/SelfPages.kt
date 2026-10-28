package com.example.ptt.push

/**
 * Ids of the pages this phone sent to its own user (the setup Test alert), most recent few, in memory. Such a
 * page is also received here, so its sender-side pushes (`page_state`, `page_answered`) must be ignored even once
 * the receiver's row is gone (missed, answered on another phone). Lost on process death, which only matters for
 * a push arriving after that.
 */
class SelfPages {
    private val ids = LinkedHashSet<String>()

    @Synchronized
    fun add(pageId: String) {
        ids.add(pageId)
        if (ids.size > MEMORY) ids.remove(ids.first())
    }

    @Synchronized
    fun contains(pageId: String): Boolean = pageId in ids

    private companion object {
        const val MEMORY = 16
    }
}
