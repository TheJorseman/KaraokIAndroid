package com.karaokei.core.data.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KaraokeDatabase =
        Room.databaseBuilder(context, KaraokeDatabase::class.java, KaraokeDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideSongDao(db: KaraokeDatabase): SongDao = db.songDao()
    @Provides fun provideModelDao(db: KaraokeDatabase): ModelDao = db.modelDao()
    @Provides fun provideProcessingCacheDao(db: KaraokeDatabase): ProcessingCacheDao = db.processingCacheDao()
}
