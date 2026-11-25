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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.karaokei.feature.karaokeplayer.renderer.KaraokeRenderStyle

@Composable
fun KaraokePlayerScreen(
    songId: String,
    onBack: () -> Unit = {},
    onSongFinished: (String) -> Unit = {},
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

    var showEditDialog by remember { mutableStateOf(false) }

    // Autoplay: when the current song finishes, the ViewModel hands us
    // the next song id and the navigation layer swaps the screen.
    val nextSong by viewModel.nextSong.collectAsStateWithLifecycle()
    LaunchedEffect(nextSong) {
        nextSong?.let {
            viewModel.consumeNextSong()
            onSongFinished(it)
        }
    }

    // Party mode: keep the screen on while singing.
    val localView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        localView.keepScreenOn = true
        onDispose { localView.keepScreenOn = false }
    }

    KaraokeStageBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                PlayerHeader(
                    title = com.karaokei.core.common.text.SongTitles.prettify(state.song?.title ?: "—"),
                    subtitle = state.song?.artist?.takeIf { it.isNotBlank() } ?: "Karaoke",
                    onBack = onBack,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.error != null) {
                        Text(
                            text = state.error ?: "",
                            color = Color.White.copy(alpha = 0.85f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        val style by viewModel.karaokeStyle.collectAsStateWithLifecycle()
                        KaraokeLyricsRenderer(
                            state = engineState,
                            renderStyle = KaraokeRenderStyle(
                                activeFontSp = style.fontSizeSp,
                                activeColor = parseColor(style.activeColor, Color(0xFFFFD740)),
                                upcomingColor = parseColor(style.upcomingColor, Color(0xB3E5FC)),
                                shadow = style.shadowEnabled,
                            ),
                        )
                    }
                }
                PlayerControls(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    isPlaying = state.isPlaying,
                    lyricsOffsetMs = state.lyricsOffsetMs,
                    editEnabled = engineState is com.karaokei.feature.karaoke.engine.KaraokeState.Active,
                    onEditLine = { showEditDialog = true },
                    onSeek = { viewModel.seekTo(it) },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onNudgeOffset = { deltaMs -> viewModel.nudgeLyricsOffset(deltaMs) },
                )
            }
        }
    }

    if (showEditDialog) {
        val active = engineState as? com.karaokei.feature.karaoke.engine.KaraokeState.Active
        EditLineDialog(
            initialText = active?.line?.words?.joinToString(" ") { it.text } ?: "",
            onConfirm = { text ->
                active?.let {
                    viewModel.editLine(songId, it.lineIndex, text)
                }
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
        )
    }
}

@Composable
private fun PlayerHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.7f),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** `239000 ms` → `"3:59"`. */
internal fun formatClock(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal fun parseColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

@Composable
private fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    lyricsOffsetMs: Int,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNudgeOffset: (Int) -> Unit,
    editEnabled: Boolean,
    onEditLine: () -> Unit,
) {
    val safeDuration = if (durationMs <= 0L) 1L else durationMs
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(48.dp)) {
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
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
            )
            Text(
                text = "${formatClock(positionMs)} / ${formatClock(durationMs)}",
                color = Color.White.copy(alpha = 0.9f),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            )
        }
        OffsetRow(
            lyricsOffsetMs = lyricsOffsetMs,
            editEnabled = editEnabled,
            onEditLine = onEditLine,
            onNudgeOffset = onNudgeOffset,
        )
    }
}

@Composable
private fun OffsetRow(
    lyricsOffsetMs: Int,
    editEnabled: Boolean,
    onEditLine: () -> Unit,
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
            onClick = onEditLine,
            enabled = editEnabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Editar la línea actual",
                tint = if (editEnabled) Color.White else Color.White.copy(alpha = 0.3f),
            )
        }
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

