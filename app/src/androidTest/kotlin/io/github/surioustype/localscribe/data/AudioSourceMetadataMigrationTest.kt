package io.github.surioustype.localscribe.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioSourceMetadataMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            LocalScribeDatabase::class.java,
        )

    @Test
    fun migration5To6RetainsOriginalAudioAndExistingJobs() {
        val databaseName = "audio-source-metadata-migration"
        helper.createDatabase(databaseName, 5).apply {
            execSQL(
                "INSERT INTO audio_sources " +
                    "(id, uri, displayName, durationMs, mimeType, accessStatus, " +
                    "hasPersistedPermission, contentFingerprint) VALUES " +
                    "('audio', 'content://audio', 'Original', 1234, 'audio/wav', " +
                    "'AVAILABLE', 1, 'sha256')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            LocalScribeDatabase.MIGRATION_5_6,
        ).use { database ->
            database.query(
                "SELECT displayName, durationMs, contentFingerprint, " +
                    "recordedAtEpochMs, addedAtEpochMs, customTitle " +
                    "FROM audio_sources WHERE id = 'audio'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("Original", cursor.getString(0))
                assertEquals(1234L, cursor.getLong(1))
                assertEquals("sha256", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
            }
            database.execSQL(
                "UPDATE audio_sources SET customTitle = 'Лекция', " +
                    "addedAtEpochMs = 123456 WHERE id = 'audio'",
            )
            database.query(
                "SELECT customTitle FROM audio_sources WHERE id = 'audio'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("Лекция", cursor.getString(0))
            }
        }
    }
}
