package com.karaokei.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.karaokei.core.common.text.SongTitles
import com.karaokei.core.common.time.DurationFormat
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
            // Header card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = SongTitles.prettify(state.song?.title ?: "—"),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = buildList {
                        state.song?.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
                        state.song?.let { if (it.durationMs > 0) add(DurationFormat.mmss(it.durationMs / 1000.0)) }
                    }.joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusChip(state.song?.status)
                    }
                }
            }
            state.transientError?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Button(
                onClick = onPlay,
                enabled = state.song?.status == SongStatus.READY || state.hasKaraoke,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Reproducir karaoke")
            }
            // Always enabled: tapping re-processes the song. While a
            // pipeline is already running, the orchestrator cancels it
            // and starts a new one (see PipelineForegroundService).
            OutlinedButton(
                onClick = onProcess,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Autorenew, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Procesar / reprocesar")
            }
            OutlinedButton(
                onClick = { viewModel.retry(songId) },
                enabled = state.song?.status == SongStatus.ERROR,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Reintentar")
            }
            OutlinedButton(
                onClick = { viewModel.invalidate(songId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Borrar caché")
            }
        }
    }
}
