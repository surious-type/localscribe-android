package io.github.surioustype.localscribe.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.core.model.InstalledModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomInstalledModelRepositoryTest {
    private lateinit var database: LocalScribeDatabase
    private lateinit var repository: RoomInstalledModelRepository

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, LocalScribeDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomInstalledModelRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun retainsRevisionsAndUsesNewestInstalledRevisionByDefault() =
        runTest {
            val old = model("old-hash", installedAt = 10)
            val latest = model("new-hash", installedAt = 20)

            repository.register(old)
            repository.register(latest)

            assertEquals(
                listOf(
                    "new-hash",
                    "old-hash",
                ),
                repository
                    .observeInstalledModels()
                    .first()
                    .map {
                        it.sha256
                    }.sorted(),
            )
            assertEquals(latest, repository.getInstalledModel(MODEL_ID))
        }

    @Test
    fun exactHashLookupResolvesFrozenRevisionAndDescriptorRemovalDeletesAllRevisions() =
        runTest {
            val old = model("old-hash", installedAt = 10)
            val latest = model("new-hash", installedAt = 20)
            repository.register(old)
            repository.register(latest)

            assertEquals(old, repository.getInstalledModel(MODEL_ID, old.sha256))
            assertEquals(latest, repository.getInstalledModel(MODEL_ID, latest.sha256))

            repository.remove(MODEL_ID)

            assertEquals(emptyList<InstalledModel>(), repository.observeInstalledModels().first())
            assertNull(repository.getInstalledModel(MODEL_ID, old.sha256))
        }

    private fun model(hash: String, installedAt: Long) =
        InstalledModel(
            id = "$MODEL_ID-$hash",
            descriptorId = MODEL_ID,
            displayName = "Small",
            filePath = "/private/$MODEL_ID-$hash.bin",
            sha256 = hash,
            bytes = 100,
            installedAtEpochMs = installedAt,
            verifiedAtEpochMs = installedAt,
        )

    private companion object {
        const val MODEL_ID = "small"
    }
}
