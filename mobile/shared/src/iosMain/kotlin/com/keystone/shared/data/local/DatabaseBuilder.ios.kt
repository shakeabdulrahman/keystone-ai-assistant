package com.keystone.shared.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSHomeDirectory

fun keystoneDatabaseBuilder(): RoomDatabase.Builder<KeystoneDatabase> =
    Room.databaseBuilder<KeystoneDatabase>(name = NSHomeDirectory() + "/Documents/keystone.db")
