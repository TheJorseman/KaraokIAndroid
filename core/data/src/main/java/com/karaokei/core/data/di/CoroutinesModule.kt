package com.karaokei.core.data.di

import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    @Provides @Singleton @Dispatcher(KaraokeDispatcher.IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @Singleton @Dispatcher(KaraokeDispatcher.Default)
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @Singleton @Dispatcher(KaraokeDispatcher.Main)
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
