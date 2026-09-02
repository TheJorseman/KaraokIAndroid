package com.karaokei.android

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import com.karaokei.android.testaudio.DefaultTestAudioSeeder
import com.karaokei.feature.modelmanager.sync.CatalogSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KaraokeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var defaultTestAudioSeeder: DefaultTestAudioSeeder
    @Inject lateinit var catalogSyncer: CatalogSyncer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Always re-sync the catalog on cold start so newly added
            // entries (e.g. an OTA catalog update) appear in the
            // model manager even when the `models` table already has
            // rows from a previous install. `syncFromBundledCatalog`
            // is idempotent: existing rows are merged and local
            // download state is preserved.
            runCatching { catalogSyncer.syncFromBundledCatalog() }
                .onFailure { Log.e(TAG, "Bundled catalog sync failed", it) }
            defaultTestAudioSeeder.seed()
        }
    }

    private companion object {
        const val TAG = "KaraokeApp"
    }
}
