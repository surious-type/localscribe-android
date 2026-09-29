package io.github.surioustype.localscribe.models

import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.ports.InstalledModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class InstalledModelFileStore(
    private val modelDirectory: File,
    private val repository: InstalledModelRepository,
    private val modelMutex: Mutex,
    private val isModelActive: suspend (String) -> Boolean,
) {
    suspend fun verifiedModel(modelId: String): InstalledModel? =
        withContext(Dispatchers.IO) {
            val model = repository.getInstalledModel(modelId) ?: return@withContext null
            val file = requirePrivateFile(model.filePath)
            if (!file.isFile ||
                file.length() != model.bytes ||
                file.sha256() != model.sha256
            ) {
                null
            } else {
                model
            }
        }

    suspend fun delete(modelId: String) =
        modelMutex.withLock {
            check(!isModelActive(modelId)) { "Model is currently active" }
            val models =
                repository.observeInstalledModels().first().filter {
                    it.descriptorId == modelId
                }
            if (models.isEmpty()) return@withLock
            withContext(Dispatchers.IO) {
                val files = models.map { requirePrivateFile(it.filePath) }
                files.forEach { file ->
                    check(!file.exists() || file.delete()) { "Unable to delete model file" }
                }
            }
            repository.remove(modelId)
        }

    private fun requirePrivateFile(path: String): File {
        val directory = modelDirectory.canonicalFile
        val file = File(path).canonicalFile
        require(file.parentFile == directory) { "Model path escapes private model directory" }
        return file
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
