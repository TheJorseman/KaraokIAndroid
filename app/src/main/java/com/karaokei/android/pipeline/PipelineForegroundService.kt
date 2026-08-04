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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var currentSongId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            currentSongId?.let(orchestrator::cancel)
            stopSelf()
            return START_NOT_STICKY
        }
        val songId = intent?.getStringExtra(EXTRA_SONG_ID)
        if (songId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (currentSongId == songId) return START_STICKY
        currentSongId = songId
        startInForeground(text = "Procesando…", progress = 0)
        scope.launch {
            orchestrator.state.collectLatest { state ->
                renderNotification(state)
                if (state.stage == PipelineStageName.DONE ||
                    state.stage == PipelineStageName.ERROR ||
                    state.stage == PipelineStageName.CANCELLED
                ) {
                    stopSelf()
                }
            }
        }
        orchestrator.runAsync(songId)
        return START_STICKY
    }

    private fun renderNotification(state: com.karaokei.feature.pipeline.PipelineState) {
        val (progress, text) = when (state.stage) {
            PipelineStageName.IDLE -> state.progress to "En cola"
            PipelineStageName.SEPARATION -> state.progress to "Separando voz…"
            PipelineStageName.TRANSCRIBING -> state.progress to "Transcribiendo…"
            PipelineStageName.ALIGNING -> state.progress to "Alineando letra…"
            PipelineStageName.DONE -> 100 to "Listo"
            PipelineStageName.ERROR -> 100 to ("Error: ${state.error ?: "desconocido"}")
            PipelineStageName.CANCELLED -> 100 to "Cancelado"
        }
        startInForeground(text, progress)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .addAction(0, "Cancelar", cancelPi)
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
        const val CHANNEL_ID: String = "pipeline_progress"
        const val NOTIFICATION_ID: Int = 0x1A03
        const val EXTRA_SONG_ID: String = "song_id"
        const val ACTION_CANCEL: String = "com.karaokei.android.pipeline.CANCEL"

        fun start(context: Context, songId: String) {
            val intent = Intent(context, PipelineForegroundService::class.java)
                .putExtra(EXTRA_SONG_ID, songId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
