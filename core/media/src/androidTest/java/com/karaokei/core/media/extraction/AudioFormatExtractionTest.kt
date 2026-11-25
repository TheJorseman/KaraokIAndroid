package com.karaokei.core.media.extraction

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karaokei.core.common.result.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Hostile-input coverage for [AudioExtractor] against real fixtures
 * (generated with ffmpeg and checked into androidTest/assets):
 * every format the SAF path accepts must extract to WAV, and broken
 * inputs must fail cleanly instead of hanging or crashing.
 */
@RunWith(AndroidJUnit4::class)
class AudioFormatExtractionTest {

    private lateinit var context: Context
    private lateinit var outDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        outDir = File(context.cacheDir, "extraction-tests").apply { mkdirs() }
    }

    private fun fixture(name: String): File {
        val out = File(outDir, name)
        context.assets.open("formats/$name").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun extract(name: String): AppResult<Long> {
        val extractor = AudioExtractor(context)
        val out = File(outDir, "out-$name.wav")
        return kotlinx.coroutines.runBlocking {
            extractor.extractToWav(Uri.fromFile(fixture(name)), out)
        }
    }

    @Test
    fun flacExtractsToWav() {
        val result = extract("tone.flac")
        assertTrue("expected success, got $result", result is AppResult.Success)
        assertTrue((result as AppResult.Success).value > 1000L)
    }

    @Test
    fun m4aExtractsToWav() {
        val result = extract("tone.m4a")
        assertTrue("expected success, got $result", result is AppResult.Success)
    }

    @Test
    fun mp4AudioExtractsToWav() {
        val result = extract("tone.mp4")
        assertTrue("expected success, got $result", result is AppResult.Success)
    }

    @Test
    fun mkvExtractsToWav() {
        val result = extract("tone.mkv")
        assertTrue("expected success, got $result", result is AppResult.Success)
    }

    @Test
    fun multiTrackPicksAnAudioTrack() {
        val result = extract("multi_audio.mkv")
        assertTrue("expected success, got $result", result is AppResult.Success)
    }

    @Test
    fun corruptFileFailsCleanly() {
        val result = extract("corrupt.wav")
        assertTrue("expected failure, got $result", result is AppResult.Failure)
    }

    @Test
    fun videoWithoutAudioTrackFailsCleanly() {
        val result = extract("no_audio.mp4")
        assertTrue("expected failure, got $result", result is AppResult.Failure)
    }

    @Test
    fun truncatedFileFailsCleanly() {
        val result = extract("truncated.flac")
        assertTrue("expected failure, got $result", result is AppResult.Failure)
    }
}
