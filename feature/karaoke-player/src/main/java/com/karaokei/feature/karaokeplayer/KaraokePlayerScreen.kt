package com.karaokei.feature.karaokeplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karaokei.core.designsystem.component.KaraokeStageBackground
import com.karaokei.feature.karaoke.engine.KaraokeEngine
import com.karaokei.feature.karaoke.engine.KaraokeState
import com.karaokei.feature.karaokeplayer.renderer.KaraokeLyricsRenderer

@Composable
fun KaraokePlayerScreen(
    songId: String,
    viewModel: KaraokePlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val engine = remember(state.karaoke) {
        state.karaoke?.let { KaraokeEngine(it) }
    }
    val engineStateFlow = remember(engine) { engine?.state }
    val engineState by (engineStateFlow?.collectAsStateWithLifecycle() ?: remember { kotlinx.coroutines.flow.MutableStateFlow<KaraokeState>(KaraokeState.Idle) }
        .collectAsStateWithLifecycle())

    LaunchedEffect(songId) {
        viewModel.load(songId)
    }

    LaunchedEffect(state.positionMs, engine) {
        engine?.onTick(state.positionMs)
    }

    KaraokeStageBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = state.song?.title ?: "—",
                    color = Color.White,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.error != null) {
                        Text(state.error ?: "", color = Color.White)
                    } else {
                        KaraokeLyricsRenderer(state = engineState)
                    }
                }
                PlayerControls(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    isPlaying = state.isPlaying,
                    onSeek = { viewModel.seekTo(it) },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    val safeDuration = if (durationMs <= 0L) 1L else durationMs
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onTogglePlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pausa" else "Reproducir",
                tint = Color.White,
            )
        }
        Slider(
            value = positionMs.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..safeDuration.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${positionMs / 1000}s / ${durationMs / 1000}s",
            color = Color.White,
        )
    }
}
