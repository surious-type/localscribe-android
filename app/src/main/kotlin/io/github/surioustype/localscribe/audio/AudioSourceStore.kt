package io.github.surioustype.localscribe.audio

import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import kotlinx.coroutines.flow.Flow

/** Persistence seam implemented by the Room adapter. */
interface AudioSourceStore {
    fun observe(): Flow<List<AudioSourceRecord>>

    suspend fun get(sourceId: String): AudioSourceRecord?

    suspend fun upsert(record: AudioSourceRecord)

    /** Removes a source and reports whether another persisted owner still references its URI. */
    suspend fun remove(sourceId: String): Boolean

    /** True when a different source record still owns a persisted grant for [uri]. */
    suspend fun hasPersistedUriOwner(sourceId: String, uri: String): Boolean
}
