package com.karaokei.core.common.time

import java.util.Locale
import java.util.concurrent.TimeUnit

object DurationFormat {
    fun mmss(seconds: Double): String {
        val safe = if (seconds.isNaN() || seconds < 0) 0.0 else seconds
        val total = safe.toLong()
        val mm = TimeUnit.SECONDS.toMinutes(total)
        val ss = total - TimeUnit.MINUTES.toSeconds(mm)
        return String.format(Locale.US, "%02d:%02d", mm, ss)
    }

    fun mmssms(seconds: Double): String {
        val safe = if (seconds.isNaN() || seconds < 0) 0.0 else seconds
        val mm = (safe / 60).toInt()
        val ss = safe - mm * 60
        return String.format(Locale.US, "%02d:%06.3f", mm, ss)
    }
}
