package io.github.surioustype.localscribe.updates

import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.UpdateDownload
import io.github.surioustype.localscribe.core.model.UpdateDownloadStatus
import io.github.surioustype.localscribe.core.model.UpdateState
import io.github.surioustype.localscribe.core.ports.AppUpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlayAppUpdateManager : AppUpdateManager {
    private val state = MutableStateFlow(UpdateState(UpdateAvailability.DISABLED))
    private val download = MutableStateFlow(UpdateDownload(UpdateDownloadStatus.IDLE, 0, 0))

    override fun observeState(): StateFlow<UpdateState> = state

    override fun observeDownload(): StateFlow<UpdateDownload> = download

    override suspend fun checkForUpdate(
        currentVersion: AppVersion,
        force: Boolean,
    ): UpdateState = state.value

    override suspend fun downloadAvailableUpdate() = Unit

    override suspend fun cancelDownload() = Unit

    override suspend fun requestInstall() = Unit
}
