package com.karaokei.core.media.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin ExoPlayer wrapper. Hilt-managed singleton scoped to the
 * application so a single instance survives configuration changes and
 * is reused across screens.
 *
 * The player is only constructed on demand to avoid holding the audio
 * focus while no song is queued. Callers MUST call [release] when the
 * app process is torn down.
 */
@Singleton
class KaraokePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile private var backing: ExoPlayer? = null

    val player: ExoPlayer
        get() = backing ?: synchronized(this) {
            backing ?: ExoPlayer.Builder(context).build().also { backing = it }
        }

    fun setUri(uri: android.net.Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun setFile(path: String) {
        player.setMediaItem(MediaItem.fromUri("file://$path"))
        player.prepare()
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun isPlaying(): Boolean = player.isPlaying
    fun currentPositionMs(): Long = player.currentPosition
    fun durationMs(): Long = player.duration.coerceAtLeast(0L)

    fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
    }

    fun release() {
        backing?.release()
        backing = null
    }
}
