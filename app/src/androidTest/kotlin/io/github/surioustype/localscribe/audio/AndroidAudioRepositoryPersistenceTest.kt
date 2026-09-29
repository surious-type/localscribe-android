package io.github.surioustype.localscribe.audio

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.data.LocalScribeDatabase
import io.github.surioustype.localscribe.data.RoomAudioSourceStore
import io.github.surioustype.localscribe.data.RoomTranscriptionRepository
import io.github.surioustype.localscribe.data.SourceDeletionBlockedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidAudioRepositoryPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LocalScribeDatabase
    private lateinit var insertedUri: android.net.Uri
    private val insertedUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        runBlocking {
            database =
                Room
                    .inMemoryDatabaseBuilder(context, LocalScribeDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            insertedUri = insertMedia("localscribe-media-test.wav", wav(1))
        }
    }

    @After
    fun tearDown() {
        insertedUris.forEach { context.contentResolver.delete(it, null, null) }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun selectedMediaStoreSourceIsPersistedBeforeJobAndSurvivesRepositoryRecreation() =
        runBlocking {
            val store = RoomAudioSourceStore(database)
            val repository = AndroidAudioRepository(context, store)
            repository.refresh()
            val selected =
                repository.observeSources().first().single {
                    it.source.uri ==
                        insertedUri.toString()
                }

            // getSource is the selection boundary used immediately before createJob.
            val durableSelection = requireNotNull(repository.getSource(selected.source.id))
            assertEquals(selected.source, durableSelection.source)
            assertNotNull(durableSelection.contentFingerprint)
            val job =
                TranscriptionJob(
                    "job",
                    selected.source.id,
                    TranscriptionConfig("small", InferenceConfig(1)),
                    "hash",
                    JobStatus.PENDING,
                    1,
                    1,
                )
            RoomTranscriptionRepository(database).createJob(
                job,
                listOf(
                    TranscriptionChunk("chunk", "job", "small", 0, 1, ChunkStatus.PENDING, 0, 1),
                ),
            )

            val recreated = AndroidAudioRepository(context, store)
            assertNotNull(recreated.getSource(selected.source.id))
            assertEquals("job", RoomTranscriptionRepository(database).getJob("job")?.id)
        }

    @Test
    fun relinkRejectsDifferentBytesAndAcceptsRenamedIdenticalAudio() =
        runBlocking {
            val replacement = insertMedia("localscribe-media-test.wav", wav(2))
            val renamedOriginal = insertMedia("renamed-identical.wav", wav(1))
            val store = RoomAudioSourceStore(database)
            val repository = AndroidAudioRepository(context, store)
            val source =
                repository.importSource(
                    insertedUri.toString(),
                    takePersistablePermission = false,
                )
            val before = requireNotNull(store.get(source.id))

            val rejected =
                runCatching {
                    repository.relinkSource(
                        source.id,
                        replacement.toString(),
                        takePersistablePermission = false,
                    )
                }
            assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, store.get(source.id))

            repository.relinkSource(
                source.id,
                renamedOriginal.toString(),
                takePersistablePermission = false,
            )
            assertEquals(
                renamedOriginal.toString(),
                requireNotNull(store.get(source.id)).source.uri,
            )
        }

    @Test
    fun blockedSourceRemovalPreservesPrivateCopyUntilStoreAcceptsDeletion() =
        runBlocking {
            val privateCopy = File(context.filesDir, "imported-audio/blocked-removal.wav")
            privateCopy.parentFile?.mkdirs()
            privateCopy.writeBytes(wav(1))
            val record =
                AudioSourceRecord(
                    source =
                        AudioSource(
                            id = "blocked",
                            uri = Uri.fromFile(privateCopy).toString(),
                            displayName = "blocked-removal.wav",
                            durationMs = 1,
                            mimeType = "audio/wav",
                        ),
                    accessStatus = SourceAccessStatus.AVAILABLE,
                    hasPersistedPermission = false,
                    contentFingerprint = "fingerprint",
                )
            val store = RejectingSourceStore(record)

            val result =
                runCatching {
                    AndroidAudioRepository(context, store).removeSource("blocked")
                }

            assertTrue(result.exceptionOrNull() is SourceDeletionBlockedException)
            assertTrue(privateCopy.exists())
            privateCopy.delete()
            Unit
        }

    private fun insertMedia(displayName: String, content: ByteArray): Uri =
        requireNotNull(
            context.contentResolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                },
            ),
        ).also { uri ->
            insertedUris += uri
            context.contentResolver.openOutputStream(uri)?.use { it.write(content) }
        }

    private fun wav(sample: Int): ByteArray =
        ByteArray(44 + 2).apply {
            fun putInt(offset: Int, value: Int) {
                this[offset] = value.toByte()
                this[offset + 1] = (value shr 8).toByte()
                this[offset + 2] = (value shr 16).toByte()
                this[offset + 3] = (value shr 24).toByte()
            }

            fun putShort(offset: Int, value: Int) {
                this[offset] = value.toByte()
                this[offset + 1] =
                    (value shr 8).toByte()
            }
            "RIFF".toByteArray().copyInto(this, 0)
            putInt(4, 38)
            "WAVEfmt ".toByteArray().copyInto(this, 8)
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, 1)
            putInt(24, 16_000)
            putInt(28, 32_000)
            putShort(32, 2)
            putShort(34, 16)
            "data".toByteArray().copyInto(this, 36)
            putInt(40, 2)
            this[44] = sample.toByte()
        }

    private class RejectingSourceStore(
        private val record: io.github.surioustype.localscribe.core.model.AudioSourceRecord,
    ) : AudioSourceStore {
        override fun observe(): Flow<List<AudioSourceRecord>> = emptyFlow()

        override suspend fun get(sourceId: String) = record.takeIf { it.source.id == sourceId }

        override suspend fun upsert(record: AudioSourceRecord) = Unit

        override suspend fun remove(sourceId: String): Nothing =
            throw SourceDeletionBlockedException(sourceId)

        override suspend fun hasPersistedUriOwner(sourceId: String, uri: String) = false
    }
}
