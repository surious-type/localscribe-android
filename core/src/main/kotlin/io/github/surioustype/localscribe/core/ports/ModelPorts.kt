package io.github.surioustype.localscribe.core.ports

import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelDownload
import kotlinx.coroutines.flow.Flow

interface ModelCatalogRepository {
    fun observeCatalog(): Flow<List<ModelDescriptor>>

    suspend fun refresh(force: Boolean)

    suspend fun getModel(modelId: String): ModelDescriptor?
}

interface ModelDownloadManager {
    fun observeDownloads(): Flow<List<ModelDownload>>

    suspend fun enqueue(modelId: String)

    suspend fun cancel(modelId: String)

    suspend fun retry(modelId: String)
}

interface InstalledModelRepository {
    fun observeInstalledModels(): Flow<List<InstalledModel>>

    suspend fun getInstalledModel(modelId: String): InstalledModel?

    suspend fun getInstalledModel(modelId: String, sha256: String): InstalledModel? =
        getInstalledModel(modelId)?.takeIf { it.sha256.equals(sha256, ignoreCase = true) }

    suspend fun register(model: InstalledModel)

    /** Removes one immutable revision without affecting retained revisions of the descriptor. */
    suspend fun remove(model: InstalledModel): Unit =
        throw UnsupportedOperationException("Exact model revision removal is not implemented")

    suspend fun remove(modelId: String)
}
