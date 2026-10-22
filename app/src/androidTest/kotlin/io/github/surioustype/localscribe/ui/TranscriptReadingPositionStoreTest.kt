package io.github.surioustype.localscribe.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranscriptReadingPositionStoreTest {
    @Test
    fun readingPositionSurvivesStoreRecreationWithoutMixingVersions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstVersion = "reading-a-" + UUID.randomUUID()
        val secondVersion = "reading-b-" + UUID.randomUUID()
        val store = TranscriptReadingPositionStore(context)

        assertNull(store.read(firstVersion))
        store.save(firstVersion, "segment-17", 42)
        store.save(secondVersion, "segment-3", 10)

        val reopened = TranscriptReadingPositionStore(context)
        assertEquals(
            TranscriptReadingPosition("segment-17", 42),
            reopened.read(firstVersion),
        )
        assertEquals(
            TranscriptReadingPosition("segment-3", 10),
            reopened.read(secondVersion),
        )

        reopened.save(firstVersion, null, -20)
        assertEquals(TranscriptReadingPosition(null, 0), reopened.read(firstVersion))
        assertEquals(
            TranscriptReadingPosition("segment-3", 10),
            reopened.read(secondVersion),
        )
    }
}
