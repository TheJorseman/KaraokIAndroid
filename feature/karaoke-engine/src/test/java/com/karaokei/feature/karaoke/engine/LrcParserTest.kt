package com.karaokei.feature.karaoke.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LrcParserTest {

    @Test fun `parses plain timestamped lines`() {
        val lrc = """
            [00:12.50]first line
            [00:15]second line
        """.trimIndent()
        val out = LrcParser.parse(lrc)
        assertThat(out).hasSize(2)
        assertThat(out[0].startSec).isWithin(1e-6).of(12.5)
        assertThat(out[0].text).isEqualTo("first line")
        assertThat(out[1].startSec).isWithin(1e-6).of(15.0)
    }

    @Test fun `supports multi-stamp lines and 3-digit fractions`() {
        val lrc = "[01:00.250][03:10.000]chorus"
        val out = LrcParser.parse(lrc)
        assertThat(out).hasSize(2)
        assertThat(out[0].startSec).isWithin(1e-6).of(60.25)
        assertThat(out[1].startSec).isWithin(1e-6).of(190.0)
        assertThat(out[0].text).isEqualTo("chorus")
    }

    @Test fun `ignores metadata tags and empty lines`() {
        val lrc = """
            [ar:Some Artist]
            [ti:Title]
            [00:05.00]
            [00:10.00]hello
        """.trimIndent()
        val out = LrcParser.parse(lrc)
        assertThat(out).hasSize(1)
        assertThat(out[0].text).isEqualTo("hello")
    }

    @Test fun `output is sorted by time`() {
        val lrc = """
            [00:20.00]later
            [00:10.00]earlier
        """.trimIndent()
        val out = LrcParser.parse(lrc)
        assertThat(out.map { it.text }).containsExactly("earlier", "later").inOrder()
    }
}
