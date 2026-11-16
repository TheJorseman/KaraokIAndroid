package com.karaokei.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karaokei.core.common.text.SongTitles
import com.karaokei.core.common.time.DurationFormat
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.db.entity.SongStatus

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LibraryScreen(
    onSongClick: (String) -> Unit,
    onPickFile: () -> Unit,
    onProcess: (String) -> Unit,
    onProcessAll: (List<String>) -> Unit = {},
    onOpenModelManager: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val pendingIds = songs.filter { it.status != SongStatus.READY }.map { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca") },
                actions = {
                    if (pendingIds.size > 1) {
                        TextButton(onClick = { onProcessAll(pendingIds) }) { Text("Procesar todo") }
                    }
                    TextButton(onClick = onOpenModelManager) { Text("Modelos") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPickFile,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Importar") },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (songs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No hay canciones. Toca \"Importar\" para empezar.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = songs, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            onClick = { onSongClick(song.id) },
                            onProcess = { onProcess(song.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    onProcess: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Leading status medallion
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = statusColors(song.status).container,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = statusIcon(song.status),
                    contentDescription = null,
                    tint = statusColors(song.status).content,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = SongTitles.prettify(song.title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildList {
                    song.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (song.durationMs > 0) add(DurationFormat.mmss(song.durationMs / 1000.0))
                }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(modifier = Modifier.padding(top = 6.dp)) {
                    StatusChip(song.status)
                }
            }
            if (song.status != SongStatus.READY) {
                TextButton(onClick = onProcess) { Text("Procesar") }
            }
        }
    }
}

private data class StatusColors(val container: Color, val content: Color)

@Composable
private fun statusColors(status: SongStatus): StatusColors = when (status) {
    SongStatus.IMPORTED -> StatusColors(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SongStatus.SEPARATING, SongStatus.TRANSCRIBING, SongStatus.ALIGNING -> StatusColors(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    )
    SongStatus.READY -> StatusColors(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    SongStatus.ERROR -> StatusColors(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
}

private fun statusIcon(status: SongStatus) = when (status) {
    SongStatus.IMPORTED -> Icons.Outlined.Input
    SongStatus.SEPARATING -> Icons.Outlined.GraphicEq
    SongStatus.TRANSCRIBING -> Icons.Outlined.Subtitles
    SongStatus.ALIGNING -> Icons.Outlined.Timeline
    SongStatus.READY -> Icons.Outlined.CheckCircle
    SongStatus.ERROR -> Icons.Outlined.ErrorOutline
}

@Composable
internal fun StatusChip(status: SongStatus?) {
    if (status == null) return
    val colors = statusColors(status)
    Row(
        modifier = Modifier
            .background(color = colors.container, shape = CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = colors.content,
        )
    }
}

private fun statusLabel(status: SongStatus): String = when (status) {
    SongStatus.IMPORTED -> "Importada"
    SongStatus.SEPARATING -> "Separando voz…"
    SongStatus.TRANSCRIBING -> "Transcribiendo…"
    SongStatus.ALIGNING -> "Alineando…"
    SongStatus.READY -> "Lista"
    SongStatus.ERROR -> "Error"
}
