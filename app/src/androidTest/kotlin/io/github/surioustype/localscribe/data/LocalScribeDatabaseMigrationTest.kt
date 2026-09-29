package io.github.surioustype.localscribe.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalScribeDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            LocalScribeDatabase::class.java,
        )

    @Test
    fun migration1To2PreservesSourcesAndAddsModelHashIndex() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO audio_sources " +
                    "(id, uri, displayName, durationMs, mimeType, accessStatus, " +
                    "hasPersistedPermission) VALUES ('source', 'content://source', " +
                    "'Lecture', 1000, 'audio/wav', 'AVAILABLE', 1)",
            )
            execSQL(
                "INSERT INTO model_benchmarks " +
                    "(id, deviceId, modelId, modelHash, threadCount, translateToEnglish, " +
                    "temperature, audioDurationMs, processingDurationMs, realTimeFactor, " +
                    "realTimeMultiplier, createdAtEpochMs) VALUES ('benchmark', 'device', " +
                    "'small', 'hash', 4, 0, 0, 1000, 500, 0.5, 2.0, 1)",
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                2,
                true,
                LocalScribeDatabase.MIGRATION_1_2,
            ).use { database ->
                database
                    .query(
                        "SELECT displayName FROM audio_sources WHERE id = 'source'",
                    ).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals("Lecture", cursor.getString(0))
                    }
                database
                    .query(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                            "AND name = 'index_transcription_jobs_modelHash'",
                    ).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(1, cursor.getInt(0))
                    }
                database
                    .query(
                        "SELECT sampleId FROM model_benchmarks WHERE id = 'benchmark'",
                    ).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals("unspecified", cursor.getString(0))
                    }
            }
    }

    @Test
    fun migration2To3PreservesInstalledRevisionAndAllowsAnotherHash() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL(
                "INSERT INTO installed_models " +
                    "(id, descriptorId, displayName, filePath, sha256, bytes, " +
                    "installedAtEpochMs, verifiedAtEpochMs) VALUES " +
                    "('small-old', 'small', 'Small', '/private/small-old.bin', " +
                    "'old-hash', 100, 1, 1)",
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                3,
                true,
                LocalScribeDatabase.MIGRATION_2_3,
            ).use { database ->
                database.execSQL(
                    "INSERT INTO installed_models " +
                        "(id, descriptorId, displayName, filePath, sha256, bytes, " +
                        "installedAtEpochMs, verifiedAtEpochMs) VALUES " +
                        "('small-new', 'small', 'Small', '/private/small-new.bin', " +
                        "'new-hash', 101, 2, 2)",
                )
                database
                    .query(
                        "SELECT sha256 FROM installed_models WHERE descriptorId = 'small' " +
                            "ORDER BY sha256",
                    ).use { cursor ->
                        val hashes =
                            buildList {
                                while (cursor.moveToNext()) add(cursor.getString(0))
                            }
                        assertEquals(listOf("new-hash", "old-hash"), hashes)
                    }
                database
                    .query(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                            "AND name = 'index_installed_models_descriptorId_sha256'",
                    ).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(1, cursor.getInt(0))
                    }
            }
    }

    @Test
    fun migration3To4AddsNullableSourceFingerprintWithoutLosingSource() {
        helper.createDatabase(DATABASE_NAME, 3).apply {
            execSQL(
                "INSERT INTO audio_sources " +
                    "(id, uri, displayName, durationMs, mimeType, accessStatus, " +
                    "hasPersistedPermission) VALUES ('source', 'content://source', " +
                    "'Lecture', 1000, 'audio/wav', 'AVAILABLE', 1)",
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                4,
                true,
                LocalScribeDatabase.MIGRATION_3_4,
            ).use { database ->
                database
                    .query(
                        "SELECT contentFingerprint FROM audio_sources WHERE id = 'source'",
                    ).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(null, cursor.getString(0))
                    }
            }
    }

    @Test
    fun migration4To5AddsNullableJobFingerprintWithoutLosingCheckpoints() {
        helper.createDatabase(DATABASE_NAME, 4).apply {
            execSQL(
                "INSERT INTO audio_sources " +
                    "(id, uri, displayName, durationMs, mimeType, accessStatus, " +
                    "hasPersistedPermission, contentFingerprint) VALUES " +
                    "('source', 'content://source', " +
                    "'Lecture', 1000, 'audio/wav', 'AVAILABLE', 1, 'source-hash')",
            )
            execSQL(
                "INSERT INTO transcription_jobs " +
                    "(id, sourceId, modelId, modelHash, threadCount, translateToEnglish, " +
                    "temperature, " +
                    "chunkDurationMs, overlapMs, contextMaxCharacters, status, createdAtEpochMs, " +
                    "updatedAtEpochMs) VALUES ('job', 'source', 'small', 'model-hash', 1, 0, 0, " +
                    "1000, 0, 100, 'PAUSED', 1, 1)",
            )
            close()
        }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                5,
                true,
                LocalScribeDatabase.MIGRATION_4_5,
            ).use { database ->
                database
                    .query("SELECT sourceFingerprint FROM transcription_jobs WHERE id = 'job'")
                    .use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(null, cursor.getString(0))
                    }
            }
    }

    @Test
    fun migration4To5AllowsFingerprintRestartToReplaceLegacyCheckpoints() =
        runBlocking {
            helper.createDatabase(RESTART_DATABASE_NAME, 4).apply {
                execSQL(
                    "INSERT INTO audio_sources " +
                        "(id, uri, displayName, durationMs, mimeType, accessStatus, " +
                        "hasPersistedPermission, contentFingerprint) VALUES " +
                        "('source', 'content://source', 'Lecture', 2000, 'audio/wav', " +
                        "'AVAILABLE', 1, 'old-source-hash')",
                )
                execSQL(
                    "INSERT INTO transcription_jobs " +
                        "(id, sourceId, modelId, modelHash, threadCount, language, " +
                        "translateToEnglish, temperature, vadModelId, vadModelHash, " +
                        "vadThreshold, vadMinimumSpeechDurationMs, vadMinimumSilenceDurationMs, " +
                        "chunkDurationMs, overlapMs, contextMaxCharacters, status, " +
                        "createdAtEpochMs, updatedAtEpochMs, completedAtEpochMs, failureCode, " +
                        "failureDiagnostic) VALUES ('job', 'source', 'small', 'model-hash', 1, " +
                        "NULL, 0, 0, NULL, NULL, NULL, NULL, NULL, 1000, 0, 100, 'RUNNING', " +
                        "1, 2, NULL, NULL, NULL)",
                )
                execSQL(
                    "INSERT INTO transcription_chunks " +
                        "(id, jobId, modelId, startMs, endMs, status, attempt, createdAtEpochMs, " +
                        "startedAtEpochMs, completedAtEpochMs, failureCode, failureDiagnostic) " +
                        "VALUES ('completed', 'job', 'small', 0, 1000, 'COMPLETED', 2, 1, 2, 3, " +
                        "NULL, NULL), ('unfinished', 'job', 'small', 1000, 2000, 'FAILED', 4, " +
                        "1, 4, 5, 'ENGINE_FAILURE', 'legacy failure')",
                )
                execSQL(
                    "INSERT INTO transcript_segments " +
                        "(id, chunkId, absoluteStartMs, absoluteEndMs, text) VALUES " +
                        "('segment', 'completed', 10, 20, 'legacy transcript')",
                )
                close()
            }

            helper
                .runMigrationsAndValidate(
                    RESTART_DATABASE_NAME,
                    5,
                    true,
                    LocalScribeDatabase.MIGRATION_4_5,
                ).close()

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database =
                Room
                    .databaseBuilder(
                        context,
                        LocalScribeDatabase::class.java,
                        RESTART_DATABASE_NAME,
                    ).allowMainThreadQueries()
                    .build()
            try {
                RoomTranscriptionRepository(database).restartForSourceFingerprint(
                    "job",
                    "replacement-source-hash",
                    100,
                )

                val sqlite = database.openHelper.readableDatabase
                sqlite
                    .query(
                        "SELECT sourceFingerprint FROM transcription_jobs WHERE id = 'job'",
                    ).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals("replacement-source-hash", cursor.getString(0))
                    }
                sqlite
                    .query(
                        "SELECT status, attempt, startedAtEpochMs, completedAtEpochMs, " +
                            "failureCode, " +
                            "failureDiagnostic FROM transcription_chunks WHERE jobId = 'job' " +
                            "ORDER BY startMs",
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            assertEquals("PENDING", cursor.getString(0))
                            assertEquals(0, cursor.getInt(1))
                            assertNull(cursor.getString(2))
                            assertNull(cursor.getString(3))
                            assertNull(cursor.getString(4))
                            assertNull(cursor.getString(5))
                        }
                    }
                sqlite
                    .query(
                        "SELECT COUNT(*) FROM transcript_segments",
                    ).use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(0, cursor.getInt(0))
                    }
            } finally {
                database.close()
            }
        }

    private companion object {
        const val DATABASE_NAME = "migration-test"
        const val RESTART_DATABASE_NAME = "migration-restart-test"
    }
}
