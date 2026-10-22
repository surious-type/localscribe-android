package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.InstalledModel

/** VAD must not silently switch off when a selected Silero component is missing. */
internal fun canStartWithSilenceSkipping(
    selectedVadModelId: String?,
    installed: List<InstalledModel>,
): Boolean =
    selectedVadModelId == null ||
        installed.any { it.descriptorId == selectedVadModelId }
