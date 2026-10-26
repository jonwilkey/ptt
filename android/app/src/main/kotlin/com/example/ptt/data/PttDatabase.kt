package com.example.ptt.data

import androidx.room.Database
import androidx.room.RoomDatabase

/** Schema JSON is exported to `app/schemas/` (checked in); bump [VERSION] with a real migration. */
@Database(
    entities = [ContactEntity::class, RosterEntity::class, OutboxEntity::class, ActivePageEntity::class],
    version = PttDatabase.VERSION,
    exportSchema = true
)
abstract class PttDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun rosterDao(): RosterDao
    abstract fun outboxDao(): OutboxDao
    abstract fun activePageDao(): ActivePageDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "ptt.db"
    }
}
