package com.keystone.shared.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Small cached API responses stored as JSON (e.g. the home dashboard). For data the app
 * only ever shows as a whole, a JSON column is simpler than tables and still offline-first.
 */
@Entity(tableName = "cached_json")
data class CachedJsonEntity(
    @PrimaryKey val key: String,
    val json: String,
    val savedAtMillis: Long,
)

@Dao
abstract class CachedJsonDao {
    @Query("SELECT * FROM cached_json WHERE `key` = :key")
    abstract fun observe(key: String): Flow<CachedJsonEntity?>

    @Upsert
    abstract suspend fun put(entity: CachedJsonEntity)

    @Query("DELETE FROM cached_json")
    abstract suspend fun deleteAll()
}
