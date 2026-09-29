package io.github.surioustype.localscribe.models

import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.ports.ModelCatalogRepository
import io.github.surioustype.localscribe.network.NetworkClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI

class JsonModelCatalogRepository(
    bundledCatalog: () -> String,
    private val networkClient: NetworkClient,
    private val remoteCatalogUrl: String = DEFAULT_CATALOG_URL,
) : ModelCatalogRepository {
    private val catalog = MutableStateFlow(parseCatalog(bundledCatalog()))

    override fun observeCatalog(): Flow<List<ModelDescriptor>> = catalog

    override suspend fun refresh(force: Boolean) {
        @Suppress("UNUSED_VARIABLE")
        val explicitlyRequested = force
        val response = networkClient.get(remoteCatalogUrl, emptyMap())
        response.use {
            require(it.statusCode == 200) { "Catalog request failed with HTTP ${it.statusCode}" }
            val bytes = it.body.readBounded(MAX_CATALOG_BYTES)
            catalog.value = parseCatalog(bytes.decodeToString())
        }
    }

    override suspend fun getModel(modelId: String): ModelDescriptor? =
        catalog.value.firstOrNull {
            it.id ==
                modelId
        }

    private fun parseCatalog(text: String): List<ModelDescriptor> {
        val root =
            try {
                JSON.parseToJsonElement(text).jsonObject
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException("Invalid model catalog JSON", exception)
            }
        val entries =
            root["models"] as? JsonArray ?: throw IllegalArgumentException("Missing models array")
        require(entries.size in 1..MAX_MODELS) { "Catalog model count is outside allowed bounds" }
        val models = entries.map { parseDescriptor(it.jsonObject) }
        require(
            models.map(ModelDescriptor::id).distinct().size == models.size,
        ) { "Duplicate model id" }
        return models
    }

    private fun parseDescriptor(value: JsonObject): ModelDescriptor {
        val id = value.requiredString("id")
        require(SAFE_ID.matches(id)) { "Unsafe model id" }
        val url = value.requiredString("downloadUrl")
        requirePublicHttps(url)
        val sha = value.requiredString("sha256").lowercase()
        require(SHA256.matches(sha)) { "Invalid model SHA-256" }
        val downloadBytes = value.requiredLong("downloadBytes")
        val installedBytes = value.requiredLong("installedBytes")
        require(downloadBytes in 1..MAX_MODEL_BYTES && installedBytes in 1..MAX_MODEL_BYTES) {
            "Model size is outside allowed bounds"
        }
        val languages =
            (value["languages"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
                ?: throw IllegalArgumentException("Missing languages")
        require(
            languages.size <= MAX_LANGUAGES &&
                languages.all {
                    SAFE_LANGUAGE.matches(it)
                },
        ) { "Invalid languages" }
        return ModelDescriptor(
            id = id,
            displayName = value.requiredString("displayName").also { require(it.length <= 80) },
            version = value.requiredString("version").also { require(it.length <= 40) },
            downloadUrl = url,
            sha256 = sha,
            downloadBytes = downloadBytes,
            installedBytes = installedBytes,
            languages = languages,
            quality = enumValueOf(value.requiredString("quality")),
            kind = enumValueOf(value.requiredString("kind")),
        )
    }

    private fun requirePublicHttps(value: String) {
        val uri = URI(value)
        require(
            uri.scheme.equals("https", true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) {
            "Model URL must be public HTTPS"
        }
    }

    private fun JsonObject.requiredString(name: String) =
        get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing $name")

    private fun JsonObject.requiredLong(name: String) =
        get(name)?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("Missing $name")

    private companion object {
        const val DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/surious-type/localscribe-android/main/" +
                "models/catalog.json"
        const val MAX_CATALOG_BYTES = 512 * 1024
        const val MAX_MODEL_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_MODELS = 100
        const val MAX_LANGUAGES = 200
        val SAFE_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        val SAFE_LANGUAGE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

internal fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Response exceeds allowed size" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
