package io.github.surioustype.localscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSignalGuardTest {
    @Test
    fun `all zero audio is skipped without running Whisper`() {
        val window = AudioSignalGuard.prepare(FloatArray(16_000 * 20))

        assertTrue(window.isSilent)
        assertTrue(window.pcm.isEmpty())
        assertEquals(20_000L, window.trimmedTailMs)
    }

    @Test
    fun `speech followed by silence is trimmed without losing speech`() {
        val pcm = FloatArray(16_000 * 20) { if (it < 16_000 * 3) 0.15f else 0f }

        val window = AudioSignalGuard.prepare(pcm)

        assertFalse(window.isSilent)
        assertTrue(window.effectiveDurationMs in 3_400L..3_600L)
        assertTrue(window.trimmedTailMs > 16_000L)
        assertEquals(0.15f, window.pcm[16_000 * 3 - 1])
    }

    @Test
    fun `short internal pause does not remove following words`() {
        val pcm =
            FloatArray(16_000 * 10) {
                if (it < 16_000 * 2 || it >= 16_000 * 6) 0.07f else 0f
            }

        val window = AudioSignalGuard.prepare(pcm)

        assertFalse(window.isSilent)
        assertEquals(pcm.size, window.pcm.size)
        assertEquals(0L, window.trimmedTailMs)
    }

    @Test
    fun `quiet but real signal survives conservative gate`() {
        val pcm = FloatArray(16_000 * 5) { 0.002f }

        val window = AudioSignalGuard.prepare(pcm)

        assertFalse(window.isSilent)
        assertEquals(pcm.size, window.pcm.size)
    }

    @Test
    fun `steady non-silent noise is left to Silero VAD`() {
        val pcm = FloatArray(16_000 * 5) { 0.012f }

        val window = AudioSignalGuard.prepare(pcm)

        assertFalse(window.isSilent)
        assertEquals(0L, window.trimmedTailMs)
    }
}
