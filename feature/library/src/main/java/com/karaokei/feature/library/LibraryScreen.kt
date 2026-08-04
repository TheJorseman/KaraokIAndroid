package com.karaokei.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.db.entity.SongStatus

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LibraryScreen(
    onSongClick: (String) -> Unit,
    onPickFile: () -> Unit,
    onProcess: (String) -> Unit,
    onOpenModelManager: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca") },
                actions = {
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
        Column(modifier = Modifier.padding(16.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium)
            song.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                Text(artist, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = statusLabel(song.status),
                style = MaterialTheme.typography.labelSmall,
            )
            if (song.status != SongStatus.READY) {
                TextButton(onClick = onProcess) { Text("Procesar") }
            }
        }
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
