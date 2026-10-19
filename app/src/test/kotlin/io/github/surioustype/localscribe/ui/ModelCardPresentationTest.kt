package io.github.surioustype.localscribe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCardPresentationTest {
    @Test
    fun `installed model wins over stale verifying event`() {
        val state = modelCardPresentation(true, "VERIFYING")

        assertEquals("Установлена", state.statusLabel)
        assertFalse(state.busy)
        assertFalse(state.canDownload)
        assertTrue(state.canDelete)
    }

    @Test
    fun `pending click disables download before state flow catches up`() {
        val state = modelCardPresentation(false, null, requestPending = true)

        assertTrue(state.busy)
        assertFalse(state.canDownload)
        assertEquals("Подготовка скачивания…", state.statusLabel)
    }

    @Test
    fun `active download and verification never show duplicate download button`() {
        for (status in listOf("QUEUED", "DOWNLOADING", "VERIFYING")) {
            val state = modelCardPresentation(false, status)
            assertTrue(state.busy)
            assertFalse(state.canDownload)
        }
    }

    @Test
    fun `failed and cancelled transfers allow retry`() {
        assertTrue(modelCardPresentation(false, "FAILED").canDownload)
        assertTrue(modelCardPresentation(false, "CANCELLED").canDownload)
    }
}
