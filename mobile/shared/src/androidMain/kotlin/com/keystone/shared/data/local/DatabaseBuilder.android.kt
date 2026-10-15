package com.keystone.shared.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun keystoneDatabaseBuilder(context: Context): RoomDatabase.Builder<KeystoneDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<KeystoneDatabase>(
        context = appContext,
        name = appContext.getDatabasePath("keystone.db").absolutePath,
    )
}
