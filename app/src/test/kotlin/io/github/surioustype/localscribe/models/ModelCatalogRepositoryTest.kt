package io.github.surioustype.localscribe.models

import io.github.surioustype.localscribe.network.ByteArrayNetworkResponse
import io.github.surioustype.localscribe.network.NetworkClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelCatalogRepositoryTest {
    @Test
    fun `bundled catalog recommends multilingual small and keeps VAD separate`() =
        runTest {
            val repository =
                JsonModelCatalogRepository(
                    bundledCatalog = { VALID_CATALOG },
                    networkClient = NetworkClient { _, _ -> error("network must not be used") },
                )

            val models = repository.observeCatalog().first()

            assertEquals("small", models.single { it.quality.name == "BALANCED" }.id)
            assertEquals("VAD", models.single { it.id == "silero-v6.2.0" }.kind.name)
        }

    @Test
    fun `refresh rejects unsafe paths and retains trusted bundled catalog`() =
        runTest {
            val repository =
                JsonModelCatalogRepository(
                    bundledCatalog = { VALID_CATALOG },
                    networkClient =
                        NetworkClient {
                            _,
                            _,
                            ->
                            ByteArrayNetworkResponse(200, bytes = UNSAFE_CATALOG.toByteArray())
                        },
                )

            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { repository.refresh(force = true) }
            }
            assertEquals(
                listOf("small", "silero-v6.2.0"),
                repository.observeCatalog().first().map { it.id },
            )
        }

    private companion object {
        const val SMALL_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + "ggml-small.bin"
        const val SILERO_MODEL_URL =
            "https://huggingface.co/ggml-org/whisper-vad/resolve/main/" + "ggml-silero-v6.2.0.bin"

        const val VALID_CATALOG = """{"models":[
          {
            "id":"small", "displayName":"Small multilingual", "version":"main",
            "downloadUrl":"$SMALL_MODEL_URL",
            "sha256":"1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            "downloadBytes":487601967, "installedBytes":487601967, "languages":["multilingual"],
            "quality":"BALANCED", "kind":"TRANSCRIPTION"
          },
          {
            "id":"silero-v6.2.0", "displayName":"Silero VAD 6.2.0", "version":"6.2.0",
            "downloadUrl":"$SILERO_MODEL_URL",
            "sha256":"2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
            "downloadBytes":885098, "installedBytes":885098, "languages":[], "quality":"LOW",
            "kind":"VAD"
          }
        ]}"""
        const val UNSAFE_CATALOG = """
            {"models":[
              {"id":"../escape", "displayName":"Bad", "version":"1",
               "downloadUrl":"https://example.com/model.bin",
               "sha256":"1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
               "downloadBytes":1, "installedBytes":1, "languages":[], "quality":"LOW",
               "kind":"TRANSCRIPTION"}
            ]}
        """
    }
}
