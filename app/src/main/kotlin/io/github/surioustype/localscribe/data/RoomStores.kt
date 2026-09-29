package io.github.surioustype.localscribe.data

import androidx.room.withTransaction
import io.github.surioustype.localscribe.audio.AudioSourceStore
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.ports.InstalledModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SourceDeletionBlockedException(sourceId: String) :
    IllegalStateException("Audio source $sourceId is referenced by a transcription job")

class RoomAudioSourceStore(
    private val database: LocalScribeDatabase,
) : AudioSourceStore {
    private val dao = database.audioSourceDao()

    override fun observe(): Flow<List<AudioSourceRecord>> =
        dao.observe().map { rows ->
            rows.map {
                it.toModel()
            }
        }

    override suspend fun get(sourceId: String): AudioSourceRecord? = dao.get(sourceId)?.toModel()

    override suspend fun upsert(record: AudioSourceRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun remove(sourceId: String): Boolean =
        database.withTransaction {
            if (dao.jobCount(sourceId) > 0) throw SourceDeletionBlockedException(sourceId)
            val record = dao.get(sourceId) ?: return@withTransaction false
            dao.delete(sourceId)
            dao.persistedUriOwnerCount(sourceId, record.uri) > 0
        }

    override suspend fun hasPersistedUriOwner(sourceId: String, uri: String): Boolean =
        dao.persistedUriOwnerCount(sourceId, uri) > 0
}

class RoomInstalledModelRepository(
    private val dao: InstalledModelDao,
) : InstalledModelRepository {
    constructor(database: LocalScribeDatabase) : this(database.installedModelDao())

    override fun observeInstalledModels(): Flow<List<InstalledModel>> =
        dao.observe().map { rows -> rows.map { it.toModel() } }

    override suspend fun getInstalledModel(modelId: String): InstalledModel? =
        dao.getByDescriptorId(modelId)?.toModel()

    override suspend fun getInstalledModel(modelId: String, sha256: String): InstalledModel? =
        dao.getByDescriptorIdAndSha256(modelId, sha256)?.toModel()

    override suspend fun register(model: InstalledModel) {
        dao.upsert(model.toEntity())
    }

    override suspend fun remove(model: InstalledModel) {
        dao.deleteById(model.id)
    }

    override suspend fun remove(modelId: String) {
        dao.deleteByDescriptorId(modelId)
    }
}

interface BenchmarkStore {
    fun observe(): Flow<List<BenchmarkRecord>>

    suspend fun get(id: String): BenchmarkRecord?

    suspend fun upsert(record: BenchmarkRecord)

    suspend fun remove(id: String)
}

class RoomBenchmarkStore(
    private val dao: BenchmarkDao,
) : BenchmarkStore {
    constructor(database: LocalScribeDatabase) : this(database.benchmarkDao())

    override fun observe(): Flow<List<BenchmarkRecord>> =
        dao.observe().map { rows ->
            rows.map {
                it.toModel()
            }
        }

    override suspend fun get(id: String): BenchmarkRecord? = dao.get(id)?.toModel()

    override suspend fun upsert(record: BenchmarkRecord) = dao.upsert(record.toEntity())

    override suspend fun remove(id: String) = dao.delete(id)
}
