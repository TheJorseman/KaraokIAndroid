package com.karaokei.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.db.typeconverter.EnumConverters

@Database(
    entities = [
        SongEntity::class,
        ModelEntity::class,
        ProcessingCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
abstract class KaraokeDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun modelDao(): ModelDao
    abstract fun processingCacheDao(): ProcessingCacheDao

    companion object {
        const val NAME: String = "karaoke.db"
    }
}
