package com.skamz.shadercam.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Shader::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shaderDao(): ShaderDao
}