package com.karaokei.core.common.coroutines

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Dispatcher(val value: KaraokeDispatcher)

enum class KaraokeDispatcher {
    IO,
    Default,
    Main,
    Unconfined,
}
