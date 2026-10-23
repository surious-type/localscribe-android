package io.github.surioustype.localscribe.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranscriptionVersionPreferencesTest {
    @Test
    fun selectedPrimaryVersionSurvivesRecreationAndIsIsolatedPerSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = "source-" + UUID.randomUUID()
        val otherSource = "source-" + UUID.randomUUID()
        val first = TranscriptionVersionPreferences(context)

        first.setPrimary(sourceId, "completed-one")
        first.setPrimary(otherSource, "completed-other")
        first.setPrimary(sourceId, "completed-two")

        val reopened = TranscriptionVersionPreferences(context)
        assertEquals("completed-two", reopened.selectedVersions.value[sourceId])
        assertEquals("completed-other", reopened.selectedVersions.value[otherSource])
    }
}
