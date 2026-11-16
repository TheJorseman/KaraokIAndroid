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
    @Inject lateinit var songDao: com.karaokei.core.data.db.dao.SongDao
    @Inject lateinit var pipelineOrchestrator: com.karaokei.feature.pipeline.PipelineOrchestrator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // The QNN HTP backend loads the Hexagon skel (e.g.
        // libQnnHtpV75Skel.so) through FastRPC, which resolves it via
        // ADSP_LIBRARY_PATH. Inside an app process that variable is
        // empty by default and the DSP finds nothing, so point it at
        // the extracted native libs before any QNN session is created.
        runCatching {
            val libDir = applicationInfo.nativeLibraryDir
            android.system.Os.setenv("ADSP_LIBRARY_PATH", libDir, true)
            android.util.Log.i(TAG, "ADSP_LIBRARY_PATH=$libDir")
        }.onFailure {
            Log.w(TAG, "could not set ADSP_LIBRARY_PATH", it)
        }
        // When the foreground service cannot be started (app in
        // background on Android 12+), run the pipeline in-process
        // instead of crashing.
        com.karaokei.android.pipeline.PipelineForegroundService.inProcessFallback =
            { songId, force -> pipelineOrchestrator.runAsync(songId, force) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // A crash mid-pipeline leaves songs stuck at
            // "Separando voz…" / "Transcribiendo…" forever. At cold
            // start no pipeline is running yet, so clear those
            // transient statuses before anything else.
            runCatching { songDao.resetTransientStatuses() }
                .onFailure { Log.e(TAG, "Failed to reset transient song statuses", it) }
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
