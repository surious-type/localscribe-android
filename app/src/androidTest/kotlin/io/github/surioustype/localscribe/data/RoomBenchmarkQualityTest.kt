package io.github.surioustype.localscribe.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.InferenceConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBenchmarkQualityTest {
    @Test
    fun persistAndRestoreQualityMetricsAlongsideSpeedAndMemory() =
        runTest {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database =
                Room.inMemoryDatabaseBuilder(context, LocalScribeDatabase::class.java).build()
            try {
                val store = RoomBenchmarkStore(database)
                val record =
                    BenchmarkRecord(
                        id = "measurement",
                        deviceId = "device",
                        modelId = "small",
                        modelHash = "hash",
                        config = InferenceConfig(threadCount = 4, language = "en"),
                        audioDurationMs = 37_000,
                        processingDurationMs = 13_000,
                        realTimeFactor = 13.0 / 37.0,
                        realTimeMultiplier = 37.0 / 13.0,
                        approximatePeakMemoryBytes = 180_000_000,
                        createdAtEpochMs = 100,
                        sampleId = "english_jfk_37s",
                        wordErrorRate = 0.2,
                        characterErrorRate = 0.1,
                    )
                store.upsert(record)

                val restored = requireNotNull(store.get("measurement"))
                assertEquals(record, restored)
                assertEquals(record, store.observe().first().single())

                store.upsert(record.copy(id = "legacy", wordErrorRate = null, characterErrorRate = null))
                val legacy = requireNotNull(store.get("legacy"))
                assertNull(legacy.wordErrorRate)
                assertNull(legacy.characterErrorRate)
            } finally {
                database.close()
            }
        }
}
