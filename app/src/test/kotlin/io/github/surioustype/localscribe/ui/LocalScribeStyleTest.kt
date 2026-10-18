package io.github.surioustype.localscribe.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalScribeStyleTest {
    @Test
    fun `audio durations render as minutes and hours`() {
        assertEquals("00:00", formatAudioDuration(0))
        assertEquals("00:45", formatAudioDuration(45_000))
        assertEquals("03:51", formatAudioDuration(231_000))
        assertEquals("1:27:20", formatAudioDuration(5_240_000))
        assertEquals("1:23:45", formatAudioDuration(5_025_000))
    }

    @Test
    fun `technical statuses have user friendly labels`() {
        assertEquals("Распознавание", localizedJobStatus("RUNNING"))
        assertEquals("Завершено", localizedJobStatus("COMPLETED"))
        assertEquals("Нужен доступ", localizedSourceStatus("PERMISSION_REQUIRED"))
    }
}
