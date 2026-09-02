package com.karaokei.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.feature.pipeline.PipelineStageName

@Composable
fun SongDetailScreen(
    songId: String,
    onPlay: () -> Unit,
    onProcess: () -> Unit,
    viewModel: SongDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val stage by viewModel.pipelineStage.collectAsState()

    LaunchedEffect(songId) { viewModel.load(songId) }
    LaunchedEffect(songId, stage) {
        if (stage == PipelineStageName.ERROR) {
            viewModel.reportTransientError("La pipeline falló. Toca Reintentar para intentarlo de nuevo.")
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.song?.title ?: "—")
            state.song?.artist?.let { Text(it) }
            Text("Estado: ${statusLabel(state.song?.status)}")
            state.transientError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onPlay,
                enabled = state.song?.status == SongStatus.READY,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reproducir karaoke") }
            // Always enabled: tapping re-processes the song. While a
            // pipeline is already running, the orchestrator cancels it
            // and starts a new one (see PipelineForegroundService).
            OutlinedButton(
                onClick = onProcess,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Procesar / reprocesar") }
            OutlinedButton(
                onClick = { viewModel.retry(songId) },
                enabled = state.song?.status == SongStatus.ERROR,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reintentar") }
            OutlinedButton(
                onClick = { viewModel.invalidate(songId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Borrar caché") }
        }
    }
}

private fun statusLabel(status: SongStatus?): String = when (status) {
    null -> "—"
    SongStatus.IMPORTED -> "Importada"
    SongStatus.SEPARATING -> "Separando…"
    SongStatus.TRANSCRIBING -> "Transcribiendo…"
    SongStatus.ALIGNING -> "Alineando…"
    SongStatus.READY -> "Lista"
    SongStatus.ERROR -> "Error"
}
