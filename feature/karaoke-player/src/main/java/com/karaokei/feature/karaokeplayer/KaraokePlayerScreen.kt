package com.karaokei.feature.karaokeplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
        state.karaoke?.let { KaraokeEngine(it, initialOffsetMs = state.lyricsOffsetMs) }
    }
    val engineStateFlow = remember(engine) { engine?.state }
    val engineState by (engineStateFlow?.collectAsStateWithLifecycle() ?: remember {
        kotlinx.coroutines.flow.MutableStateFlow<KaraokeState>(KaraokeState.Idle)
    }.collectAsStateWithLifecycle())

    LaunchedEffect(songId) {
        viewModel.load(songId)
    }

    LaunchedEffect(state.positionMs, engine) {
        engine?.onTick(state.positionMs)
    }

    // Push any preference-driven offset updates into the resolver so
    // the engine stays in sync when the user changes the offset from
    // a different screen (e.g. a settings page).
    LaunchedEffect(engine, state.lyricsOffsetMs) {
        engine?.setOffsetMs(state.lyricsOffsetMs)
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
                    lyricsOffsetMs = state.lyricsOffsetMs,
                    onSeek = { viewModel.seekTo(it) },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onNudgeOffset = { deltaMs -> viewModel.nudgeLyricsOffset(deltaMs) },
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
    lyricsOffsetMs: Int,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNudgeOffset: (Int) -> Unit,
) {
    val safeDuration = if (durationMs <= 0L) 1L else durationMs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        OffsetRow(lyricsOffsetMs = lyricsOffsetMs, onNudgeOffset = onNudgeOffset)
    }
}

@Composable
private fun OffsetRow(
    lyricsOffsetMs: Int,
    onNudgeOffset: (Int) -> Unit,
) {
    val seconds = lyricsOffsetMs / 1000.0
    val sign = if (seconds > 0.05) "+" else if (seconds < -0.05) "−" else "±"
    val magnitude = abs(seconds)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Letras", color = Color.White.copy(alpha = 0.7f))
        IconButton(
            onClick = { onNudgeOffset(-100) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FastRewind,
                contentDescription = "Adelantar letras 100 ms",
                tint = Color.White,
            )
        }
        Text(
            text = "$sign${"%.1f".format(magnitude)} s",
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onNudgeOffset(+100) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FastForward,
                contentDescription = "Retrasar letras 100 ms",
                tint = Color.White,
            )
        }
    }
}

private fun abs(x: Double): Double = if (x < 0) -x else x

