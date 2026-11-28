package com.karaokei.android.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.karaokei.android.MainActivity
import com.karaokei.android.R
import com.karaokei.feature.pipeline.PipelineOrchestrator
import com.karaokei.feature.pipeline.PipelineStageName
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that hosts the [PipelineOrchestrator].
 *
 * The service is bound to a single song; once the pipeline reaches
 * the `DONE` or `ERROR` terminal state, it stops itself. The user can
 * cancel the run via the notification action.
 */
@AndroidEntryPoint
class PipelineForegroundService : Service() {

    @Inject lateinit var orchestrator: PipelineOrchestrator
    @Inject lateinit var syncOnlineLyrics: com.karaokei.feature.modelmanager.lyrics.SyncOnlineLyricsUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var currentSongId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            currentSongId?.let(orchestrator::cancel)
            orchestrator.clearQueue()
            stopSelf()
            return START_NOT_STICKY
        }
        val queueMode = intent?.action == ACTION_QUEUE
        val songId = intent?.getStringExtra(EXTRA_SONG_ID)
        if (songId.isNullOrBlank() && !queueMode) {
            stopSelf()
            return START_NOT_STICKY
        }
        val force = intent?.getBooleanExtra(EXTRA_FORCE_REPROCESS, false) ?: false
        // Always re-launch the orchestrator (even if the same song
        // ID is already running). `PipelineOrchestrator.runAsync`
        // cancels the active job before launching a new one, so the
        // previous pipeline is interrupted cleanly. This makes the
        // "Procesar" button a true re-process trigger instead of a
        // silent no-op that the user complained about.
        currentSongId = songId
        // Re-subscribe to orchestrator state every time the service
        // gets a fresh start command, so the notification stays in
        // sync after the system kills and recreates the service.
        // Terminal states are only acted upon when they belong to the
        // run started by THIS command: the orchestrator state may
        // still hold the DONE of a previous run, which would stop the
        // service instantly and the real run's DONE would never be
        // observed (lyrics sync silently skipped).
        val serviceStartMs = System.currentTimeMillis()
        stateJob?.cancel()
        stateJob = scope.launch {
            orchestrator.state.collectLatest { state ->
                renderNotification(state)
                val fresh = (state.startedAtMs ?: 0L) >= serviceStartMs - 1_000L
                if (state.stage == PipelineStageName.DONE && fresh) {
                    // Best-effort: fetch real lyrics and re-sync them
                    // onto the local timestamps. Enqueued through
                    // WorkManager so it survives this service stopping
                    // and retries when internet appears; it never
                    // fails the pipeline.
                    val songId = state.songId
                    if (songId != null) {
                        runCatching { syncOnlineLyrics.enqueue(songId) }
                    }
                    // Keep the service alive while the batch queue has
                    // pending songs ("Procesar todo").
                    if (orchestrator.pendingCount.value > 0) {
                        renderNotification(
                            state,
                            overrideText = "En cola… (${orchestrator.pendingCount.value} canciones)",
                        )
                    } else {
                        stopSelf()
                    }
                }
                if ((state.stage == PipelineStageName.ERROR ||
                        state.stage == PipelineStageName.CANCELLED) && fresh
                ) {
                    // With a batch queue an error in one song must not
                    // take the remaining songs down with it.
                    if (orchestrator.pendingCount.value == 0) {
                        stopSelf()
                    }
                }
            }
        }
        if (queueMode) {
            // Batch mode: the orchestrator's queue consumer runs the
            // enqueued songs; this service only hosts the notification
            // and the foreground priority.
            startInForeground("En cola…", 0)
        } else {
            orchestrator.runAsync(songId!!, force = force)
        }
        return START_STICKY
    }

    private fun renderNotification(
        state: com.karaokei.feature.pipeline.PipelineState,
        overrideText: String? = null,
    ) {
        val (progress, text) = when (state.stage) {
            PipelineStageName.IDLE -> state.progress to "En cola"
            PipelineStageName.SEPARATION ->
                if (state.testFixture) state.progress to "Procesando canción de prueba…"
                else state.progress to "Separando voz…"
            PipelineStageName.TRANSCRIBING ->
                if (state.testFixture) state.progress to "Transcribiendo (demo)…"
                else state.progress to "Transcribiendo…"
            PipelineStageName.ALIGNING ->
                if (state.testFixture) state.progress to "Alineando (demo)…"
                else state.progress to "Alineando letra…"
            PipelineStageName.DONE -> 100 to "Listo"
            PipelineStageName.ERROR -> 100 to ("Error: ${state.error ?: "desconocido"}")
            PipelineStageName.CANCELLED -> 100 to "Cancelado"
        }
        val eta = state.etaRemainingMs()
        val withEta = if (eta != null) "$text · ${com.karaokei.feature.pipeline.formatEta(eta)}" else text
        startInForeground(overrideText ?: withEta, if (overrideText != null) 0 else progress)
    }

    private fun startInForeground(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        ensureChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = Intent(this, PipelineForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Title shows the percentage so users see real progress at a
        // glance from the notification shade. We avoid the indeterminate
        // flag (which would hide the bar) and pass `false` whenever we
        // have any progress to render, even at 0–1%.
        val isIndeterminate = progress <= 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$text — ${progress.coerceIn(0, 100)}%")
            .setSmallIcon(R.drawable.ic_pipeline_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, progress.coerceIn(0, 100), isIndeterminate)
            .addAction(0, "Cancelar", cancelPi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Procesamiento de canciones",
                    NotificationManager.IMPORTANCE_LOW,
                )
                channel.setShowBadge(false)
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stateJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG: String = "PipelineForegroundService"
        const val CHANNEL_ID: String = "pipeline_progress"
        const val NOTIFICATION_ID: Int = 0x1A03
        const val EXTRA_SONG_ID: String = "song_id"
        const val EXTRA_FORCE_REPROCESS: String = "force_reprocess"
        const val ACTION_CANCEL: String = "com.karaokei.android.pipeline.CANCEL"
        const val ACTION_QUEUE: String = "com.karaokei.android.pipeline.QUEUE"

        /**
         * Fallback used when the foreground service cannot be started
         * (Android 12+ throws ForegroundServiceStartNotAllowedException
         * for background starts). Registered by `KaraokeApp` so the
         * pipeline still runs in-process instead of crashing the app.
         */
        @Volatile
        var inProcessFallback: ((String, Boolean) -> Unit)? = null

        fun start(context: Context, songId: String, forceReprocess: Boolean = false) {
            val intent = Intent(context, PipelineForegroundService::class.java)
                .putExtra(EXTRA_SONG_ID, songId)
                .putExtra(EXTRA_FORCE_REPROCESS, forceReprocess)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "foreground service start failed (${t.message}); " +
                        "running pipeline in-process without notification",
                )
                val fallback = inProcessFallback
                if (fallback != null) {
                    fallback(songId, forceReprocess)
                } else {
                    Log.e(TAG, "no in-process fallback registered; pipeline not started")
                }
            }
        }

        /**
         * Start the service in batch mode: the songs already enqueued in
         * [PipelineOrchestrator.enqueue] are run by its queue consumer;
         * this service only hosts the notification/foreground priority.
         * If the foreground start is not allowed, the queue keeps
         * processing in-process anyway (just without a notification).
         */
        fun startQueue(context: Context) {
            val intent = Intent(context, PipelineForegroundService::class.java)
                .setAction(ACTION_QUEUE)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "queue foreground start failed (${t.message}); continuing in-process")
            }
        }
    }
}
