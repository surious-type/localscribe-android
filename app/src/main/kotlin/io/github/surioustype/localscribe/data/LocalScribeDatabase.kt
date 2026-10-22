package io.github.surioustype.localscribe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AudioSourceEntity::class,
        TranscriptionJobEntity::class,
        TranscriptionChunkEntity::class,
        TranscriptSegmentEntity::class,
        InstalledModelEntity::class,
        BenchmarkEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class LocalScribeDatabase : RoomDatabase() {
    abstract fun audioSourceDao(): AudioSourceDao

    abstract fun transcriptionDao(): TranscriptionDao

    abstract fun installedModelDao(): InstalledModelDao

    abstract fun benchmarkDao(): BenchmarkDao

    companion object {
        fun open(context: Context): LocalScribeDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    LocalScribeDatabase::class.java,
                    "localscribe.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                ).build()

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE model_benchmarks ADD COLUMN wordErrorRate REAL")
                    db.execSQL("ALTER TABLE model_benchmarks ADD COLUMN characterErrorRate REAL")
                }
            }

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE model_benchmarks ADD COLUMN sampleId TEXT NOT NULL " +
                            "DEFAULT 'unspecified'",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_transcription_jobs_modelHash " +
                            "ON transcription_jobs(modelHash)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_model_benchmarks_sampleId " +
                            "ON model_benchmarks(sampleId)",
                    )
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP INDEX IF EXISTS index_installed_models_descriptorId")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_installed_models_descriptorId_sha256 " +
                            "ON installed_models(descriptorId, sha256)",
                    )
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE audio_sources ADD COLUMN contentFingerprint TEXT")
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE audio_sources ADD COLUMN recordedAtEpochMs INTEGER")
                    db.execSQL("ALTER TABLE audio_sources ADD COLUMN addedAtEpochMs INTEGER")
                    db.execSQL("ALTER TABLE audio_sources ADD COLUMN customTitle TEXT")
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transcription_jobs ADD COLUMN sourceFingerprint TEXT")
                }
            }
    }
}
