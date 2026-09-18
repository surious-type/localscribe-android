package io.github.surioustype.localscribe.core.ports

import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.UpdateDownload
import io.github.surioustype.localscribe.core.model.UpdateState
import kotlinx.coroutines.flow.Flow

interface AppUpdateManager {
    fun observeState(): Flow<UpdateState>

    fun observeDownload(): Flow<UpdateDownload>

    suspend fun checkForUpdate(currentVersion: AppVersion, force: Boolean): UpdateState

    suspend fun downloadAvailableUpdate()

    suspend fun cancelDownload()

    suspend fun requestInstall()
}
