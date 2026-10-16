package com.keystone.shared.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * On-device cache of the user's conversation history.
 * It's a cache, not the source of truth: the server is. Version bumps that aren't worth a
 * migration can simply drop and refetch (see [buildKeystoneDatabase]).
 */
@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 2)
@ConstructedBy(KeystoneDatabaseConstructor::class)
abstract class KeystoneDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}

// Room's KSP processor generates the `actual` for every platform.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object KeystoneDatabaseConstructor : RoomDatabaseConstructor<KeystoneDatabase> {
    override fun initialize(): KeystoneDatabase
}

/** Finishes a platform-specific builder (see androidMain / iosMain) the same way everywhere. */
fun buildKeystoneDatabase(builder: RoomDatabase.Builder<KeystoneDatabase>): KeystoneDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // A cache can be rebuilt from the server, so a schema change just starts it fresh.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
