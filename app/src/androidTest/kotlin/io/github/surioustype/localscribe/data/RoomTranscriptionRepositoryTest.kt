package io.github.surioustype.localscribe.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTranscriptionRepositoryTest {
    private lateinit var database: LocalScribeDatabase
    private lateinit var repository: RoomTranscriptionRepository

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, LocalScribeDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomTranscriptionRepository(database)
        runBlocking {
            RoomAudioSourceStore(database).upsert(
                AudioSourceRecord(
                    AudioSource("source", "content://source", "Lecture", 180_000, "audio/wav"),
                    SourceAccessStatus.AVAILABLE,
                    true,
                ),
            )
        }
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun completionReplacesOnlyItsChunkSegmentsAndRetryIsIdempotent() =
        runTest {
            repository.createJob(job(), listOf(chunk("chunk-1", 0), chunk("chunk-2", 90_000)))
            repository.resumeJob(JOB_ID, 2)
            repository.claimNextChunk(JOB_ID, 3)
            val first = listOf(TranscriptSegment("segment-1", "chunk-1", 100, 500, "first"))

            repository.completeChunk("chunk-1", first, 4)
            repository.completeChunk("chunk-1", first, 5)
            repository.claimNextChunk(JOB_ID, 6)
            repository.completeChunk(
                "chunk-2",
                listOf(TranscriptSegment("segment-2", "chunk-2", 90_100, 90_500, "second")),
                7,
            )

            assertEquals(
                listOf(
                    "segment-1",
                    "segment-2",
                ),
                repository.observeSegments(JOB_ID).first().map {
                    it.id
                },
            )
            assertEquals(listOf(1, 1), repository.observeChunks(JOB_ID).first().map { it.attempt })
        }

    @Test
    fun cancellationWinsAgainstLateChunkCompletion() =
        runTest {
            repository.createJob(job(), listOf(chunk("chunk-1", 0)))
            repository.resumeJob(JOB_ID, 2)
            repository.claimNextChunk(JOB_ID, 3)
            repository.cancelJob(JOB_ID, 4)

            try {
                repository.completeChunk(
                    "chunk-1",
                    listOf(TranscriptSegment("late", "chunk-1", 0, 100, "late")),
                    5,
                )
                fail("Late completion must be rejected")
            } catch (_: PersistenceStateException) {
                // Expected: cancellation is durable before the late native result arrives.
            }

            assertEquals(JobStatus.CANCELLED, repository.getJob(JOB_ID)?.status)
            assertEquals(
                ChunkStatus.CANCELLED,
                repository
                    .observeChunks(JOB_ID)
                    .first()
                    .single()
                    .status,
            )
            assertEquals(emptyList<TranscriptSegment>(), repository.observeSegments(JOB_ID).first())
        }

    @Test
    fun coldRecoveryPreservesCompletedChunksAndRequeuesOnlyProcessingChunks() =
        runTest {
            repository.createJob(job(), listOf(chunk("chunk-1", 0), chunk("chunk-2", 90_000)))
            repository.resumeJob(JOB_ID, 2)
            repository.claimNextChunk(JOB_ID, 3)
            repository.completeChunk(
                "chunk-1",
                listOf(TranscriptSegment("done", "chunk-1", 0, 100, "done")),
                4,
            )
            repository.claimNextChunk(JOB_ID, 5)

            val summary = repository.recoverInterrupted(6)

            assertEquals(1, summary.recoveredChunkCount)
            assertEquals(1, summary.pausedJobCount)
            assertEquals(JobStatus.PAUSED, repository.getJob(JOB_ID)?.status)
            assertEquals(
                listOf(ChunkStatus.COMPLETED, ChunkStatus.PENDING),
                repository.observeChunks(JOB_ID).first().map { it.status },
            )
            assertEquals(
                "done",
                repository
                    .observeSegments(JOB_ID)
                    .first()
                    .single()
                    .text,
            )
        }

    @Test
    fun sourceIdentityRestartDeletesSegmentsAndResetsEveryCheckpointAtomically() =
        runTest {
            repository.createJob(job(), listOf(chunk("chunk-1", 0), chunk("chunk-2", 90_000)))
            repository.resumeJob(JOB_ID, 2)
            repository.claimNextChunk(JOB_ID, 3)
            repository.completeChunk(
                "chunk-1",
                listOf(TranscriptSegment("done", "chunk-1", 0, 100, "done")),
                4,
            )

            repository.restartForSourceFingerprint(JOB_ID, "replacement-hash", 5)

            assertEquals("replacement-hash", repository.getJob(JOB_ID)?.sourceFingerprint)
            assertEquals(emptyList<TranscriptSegment>(), repository.observeSegments(JOB_ID).first())
            assertEquals(
                listOf(ChunkStatus.PENDING, ChunkStatus.PENDING),
                repository.observeChunks(JOB_ID).first().map { it.status },
            )
            assertEquals(listOf(0, 0), repository.observeChunks(JOB_ID).first().map { it.attempt })
        }

    @Test
    fun jobAndCompletePlanInsertRollsBackTogether() =
        runTest {
            val duplicate = chunk("duplicate", 0)

            try {
                repository.createJob(job(), listOf(duplicate, duplicate))
                fail("Duplicate chunk identity must abort the complete plan")
            } catch (_: Exception) {
                // Expected SQLite uniqueness failure; the transaction must also roll back the job.
            }

            assertEquals(null, repository.getJob(JOB_ID))
        }

    @Test
    fun sourceReferencedByTranscriptCannotBeDeleted() =
        runTest {
            repository.createJob(job(), listOf(chunk("chunk-1", 0)))
            val sourceStore = RoomAudioSourceStore(database)

            try {
                sourceStore.remove("source")
                fail("Referenced source deletion must be rejected")
            } catch (_: SourceDeletionBlockedException) {
                // The source URI is still required to resume or play the persisted transcript.
            }

            assertEquals("source", sourceStore.get("source")?.source?.id)
        }

    @Test
    fun removingOneDuplicatePersistedUriReportsTheRemainingGrantOwner() =
        runTest {
            val sourceStore = RoomAudioSourceStore(database)
            sourceStore.upsert(
                AudioSourceRecord(
                    AudioSource("duplicate", "content://source", "Copy", 180_000, "audio/wav"),
                    SourceAccessStatus.AVAILABLE,
                    true,
                    "fingerprint",
                ),
            )

            assertEquals(true, sourceStore.remove("source"))
            assertEquals("duplicate", sourceStore.get("duplicate")?.source?.id)
            assertEquals(false, sourceStore.remove("duplicate"))
        }

    @Test
    fun benchmarkStorePreservesSampleIdentity() =
        runTest {
            val store = RoomBenchmarkStore(database)
            store.upsert(
                BenchmarkRecord(
                    id = "benchmark",
                    deviceId = "device",
                    modelId = "small",
                    modelHash = "hash",
                    config = InferenceConfig(4),
                    audioDurationMs = 1_000,
                    processingDurationMs = 500,
                    realTimeFactor = 0.5,
                    realTimeMultiplier = 2.0,
                    createdAtEpochMs = 1,
                    sampleId = "jfk-37s",
                ),
            )

            assertEquals("jfk-37s", store.get("benchmark")?.sampleId)
            assertEquals(listOf("benchmark"), store.observe().first().map { it.id })
        }

    @Test
    fun repeatedPauseIsIdempotent() =
        runTest {
            repository.createJob(job(), listOf(chunk("chunk-1", 0)))
            repository.resumeJob(JOB_ID, 2)

            repository.pauseJob(JOB_ID, 3)
            repository.pauseJob(JOB_ID, 4)

            assertEquals(JobStatus.PAUSED, repository.getJob(JOB_ID)?.status)
            assertEquals(
                ChunkStatus.PAUSED,
                repository
                    .observeChunks(JOB_ID)
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun lateStopForTerminalJobsIsIdempotent() =
        runTest {
            repository.createJob(job(), listOf(chunk("chunk-1", 0)))
            repository.resumeJob(JOB_ID, 2)
            repository.claimNextChunk(JOB_ID, 3)
            repository.completeChunk("chunk-1", emptyList(), 4)
            repository.completeJob(JOB_ID, 5)

            repository.cancelJob(JOB_ID, 6)

            assertEquals(JobStatus.COMPLETED, repository.getJob(JOB_ID)?.status)
        }

    private fun job() =
        TranscriptionJob(
            id = JOB_ID,
            sourceId = "source",
            config = TranscriptionConfig("small", InferenceConfig(4)),
            modelHash = "abc123",
            status = JobStatus.PENDING,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    private fun chunk(id: String, startMs: Long) =
        TranscriptionChunk(
            id = id,
            jobId = JOB_ID,
            modelId = "small",
            startMs = startMs,
            endMs = startMs + 90_000,
            status = ChunkStatus.PENDING,
            attempt = 0,
            createdAtEpochMs = 1,
        )

    private companion object {
        const val JOB_ID = "job"
    }
}
