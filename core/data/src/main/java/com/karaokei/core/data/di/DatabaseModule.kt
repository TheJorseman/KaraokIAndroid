package com.karaokei.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.karaokei.core.data.db.KaraokeDatabase
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * 1 -> 2: add columns the catalog now carries. Existing rows are
     * backfilled with sensible defaults so the app keeps working
     * before the user re-syncs the catalog. Songs and processing cache
     * are preserved across this migration.
     */
    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE models ADD COLUMN sidecar_url TEXT")
            db.execSQL("ALTER TABLE models ADD COLUMN sidecar_path TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KaraokeDatabase =
        Room.databaseBuilder(context, KaraokeDatabase::class.java, KaraokeDatabase.NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideSongDao(db: KaraokeDatabase): SongDao = db.songDao()
    @Provides fun provideModelDao(db: KaraokeDatabase): ModelDao = db.modelDao()
    @Provides fun provideProcessingCacheDao(db: KaraokeDatabase): ProcessingCacheDao = db.processingCacheDao()
}
