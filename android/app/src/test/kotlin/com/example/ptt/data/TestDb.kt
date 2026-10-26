package com.example.ptt.data

import android.content.Context
import androidx.room.Room

/** Room on Robolectric's native SQLite, with inline executors so tests are deterministic. */
object TestDb {
    fun inMemory(context: Context): PttDatabase = Room.inMemoryDatabaseBuilder(context, PttDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(Runnable::run)
        .setTransactionExecutor(Runnable::run)
        .build()
}
