package com.example.ptt.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY display_name COLLATE NOCASE, user_id")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE user_id = :userId")
    suspend fun get(userId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts")
    suspend fun clear()

    /** Replaces the whole list, which is how the server defines `GET /v1/contacts`. */
    @Transaction
    suspend fun replaceAll(contacts: List<ContactEntity>) {
        clear()
        upsertAll(contacts)
    }
}

@Dao
interface RosterDao {
    @Query("SELECT * FROM roster WHERE id = ${RosterEntity.ROW_ID}")
    fun observe(): Flow<RosterEntity?>

    @Query("SELECT * FROM roster WHERE id = ${RosterEntity.ROW_ID}")
    suspend fun get(): RosterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(roster: RosterEntity)

    @Query("DELETE FROM roster")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    /** Returns -1 (and changes nothing) when a row with this id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: OutboxEntity): Long

    /** Oldest first: per-page ordering (a page before its cancel) relies on it. */
    @Query("SELECT * FROM outbox ORDER BY created_at, rowid")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: String): OutboxEntity?

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("UPDATE outbox SET attempts = :attempts, next_attempt_at = :nextAttemptAt WHERE id = :id")
    suspend fun reschedule(id: String, attempts: Int, nextAttemptAt: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface ActivePageDao {
    @Query("SELECT * FROM active_page ORDER BY created_at DESC LIMIT 1")
    fun observe(): Flow<ActivePageEntity?>

    @Query("SELECT * FROM active_page ORDER BY created_at DESC LIMIT 1")
    suspend fun get(): ActivePageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: ActivePageEntity)

    @Update
    suspend fun update(page: ActivePageEntity): Int

    @Query("DELETE FROM active_page")
    suspend fun clear()

    @Query("DELETE FROM active_page WHERE page_id = :pageId")
    suspend fun delete(pageId: String)

    /** Starts a page or session, replacing whatever was active: only one at a time. */
    @Transaction
    suspend fun replace(page: ActivePageEntity) {
        clear()
        insert(page)
    }
}
