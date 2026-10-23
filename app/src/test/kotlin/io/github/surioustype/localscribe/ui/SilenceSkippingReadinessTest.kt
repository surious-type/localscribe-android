package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.InstalledModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceSkippingReadinessTest {
    @Test
    fun vadRequiresInstalledSileroWhenEnabledAndIsOptionalWhenDisabled() {
        val installed =
            InstalledModel(
                "row",
                "silero-v6.2.0",
                "Silero",
                "/private/silero",
                "hash",
                100L,
                1L,
                1L,
            )
        assertTrue(canStartWithSilenceSkipping(null, emptyList()))
        assertFalse(canStartWithSilenceSkipping("silero-v6.2.0", emptyList()))
        assertTrue(canStartWithSilenceSkipping("silero-v6.2.0", listOf(installed)))
        assertFalse(canStartWithSilenceSkipping("some-other-model", listOf(installed)))
    }
}
