package io.github.surioustype.localscribe.core.ports

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import kotlinx.coroutines.flow.Flow

interface AudioRepository {
    fun observeSources(): Flow<List<AudioSourceRecord>>

    suspend fun refresh()

    suspend fun getSource(sourceId: String): AudioSourceRecord?

    /** Re-reads the source identity before resuming checkpointed work. */
    suspend fun verifySource(sourceId: String): AudioSourceRecord?

    suspend fun importSource(uri: String, takePersistablePermission: Boolean): AudioSource

    /** Replaces a lost source's backing URI while retaining jobs' durable source id. */
    suspend fun relinkSource(sourceId: String, uri: String, takePersistablePermission: Boolean)

    suspend fun removeSource(sourceId: String)
}

interface AudioPipeline {
    /** Returns mono 16 kHz float PCM for [startMs, endMs). */
    suspend fun readWindow(source: AudioSource, startMs: Long, endMs: Long): FloatArray
}
