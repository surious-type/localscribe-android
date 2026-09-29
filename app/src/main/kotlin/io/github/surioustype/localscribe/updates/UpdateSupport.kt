package io.github.surioustype.localscribe.updates

import io.github.surioustype.localscribe.core.model.AppRelease
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.UpdateState
import java.io.File

interface UpdateCheckStore {
    fun cachedState(): UpdateState?

    fun save(state: UpdateState)
}

class MemoryUpdateCheckStore(initialValue: Long?) : UpdateCheckStore {
    private var value =
        initialValue?.let {
            UpdateState(UpdateAvailability.UP_TO_DATE, lastCheckedAtEpochMs = it)
        }

    override fun cachedState(): UpdateState? = value

    override fun save(state: UpdateState) {
        value = state
    }

    fun lastCheckEpochMs(): Long? = value?.lastCheckedAtEpochMs
}

interface UpdatePlatform {
    /** Returns a FileProvider URI after verifying package, versionCode, and signing lineage. */
    fun verifyAndCreateUri(apk: File, release: AppRelease): String

    fun requestInstall(localUri: String)
}
