package io.github.surioustype.localscribe.audio

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.ports.AudioRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

class AudioSourceException(
    val failure: DomainFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class AndroidAudioRepository(
    context: Context,
    private val store: AudioSourceStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maximumPrivateImportBytes: Long = 2L * 1_024 * 1_024 * 1_024,
) : AudioRepository {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val mediaSources = MutableStateFlow<List<AudioSourceRecord>>(emptyList())
    private val privateImportDirectory = File(applicationContext.filesDir, PRIVATE_IMPORT_DIRECTORY)
    private val sourceOperationMutex = Mutex()

    override fun observeSources(): Flow<List<AudioSourceRecord>> =
        combine(store.observe(), mediaSources) { imported, media ->
            (imported + media)
                .distinctBy { it.source.id }
                .sortedBy { it.source.displayName.lowercase() }
        }

    override suspend fun refresh() =
        withContext(ioDispatcher) {
            sourceOperationMutex.withLock {
                mediaSources.value = queryMediaStore()
                store.observe().firstSnapshot().forEach { record ->
                    val refreshed = record.copy(accessStatus = accessStatus(record.source.uri))
                    if (refreshed != record) store.upsert(refreshed)
                }
            }
        }

    override suspend fun getSource(sourceId: String): AudioSourceRecord? =
        withContext(ioDispatcher) {
            sourceOperationMutex.withLock {
                store
                    .get(sourceId)
                    ?.let { persistFingerprintIfMissing(it) }
                    ?: mediaSources.value
                        .firstOrNull { it.source.id == sourceId }
                        ?.let { selected ->
                            // Persist the selected MediaStore row before a job can reference it.
                            // Its fingerprint binds any future relink to these audio bytes.
                            selected
                                .copy(
                                    contentFingerprint =
                                        fingerprint(
                                            Uri.parse(selected.source.uri),
                                        ),
                                ).also { store.upsert(it) }
                        }
            }
        }

    private suspend fun persistFingerprintIfMissing(record: AudioSourceRecord): AudioSourceRecord =
        if (record.contentFingerprint != null ||
            record.accessStatus != SourceAccessStatus.AVAILABLE
        ) {
            record
        } else {
            record.copy(contentFingerprint = fingerprint(Uri.parse(record.source.uri))).also {
                store.upsert(it)
            }
        }

    override suspend fun verifySource(sourceId: String): AudioSourceRecord? =
        withContext(ioDispatcher) {
            sourceOperationMutex.withLock {
                store
                    .get(sourceId)
                    ?.let { record ->
                        if (record.accessStatus != SourceAccessStatus.AVAILABLE) {
                            record
                        } else {
                            record
                                .copy(
                                    contentFingerprint = fingerprint(Uri.parse(record.source.uri)),
                                ).also {
                                    if (it.contentFingerprint != record.contentFingerprint) {
                                        store.upsert(it)
                                    }
                                }
                        }
                    }
            }
        }

    override suspend fun importSource(
        uri: String,
        takePersistablePermission: Boolean,
    ): AudioSource =
        withContext(ioDispatcher) {
            sourceOperationMutex.withLock {
                val parsed = Uri.parse(uri)
                if (parsed.scheme != "content" && parsed.scheme != "file") {
                    throw failure(FailureCode.SOURCE_MISSING, "unsupported_source_uri")
                }
                val metadata = readMetadata(parsed)
                var persisted = false
                val alreadyPersisted =
                    parsed.scheme == "content" &&
                        resolver.persistedUriPermissions.any { it.uri == parsed }
                if (takePersistablePermission && parsed.scheme == "content") {
                    persisted = tryPersistPermission(parsed)
                }
                var durableSource: DurableSource? = null
                var source: AudioSource? = null
                var committed = false
                try {
                    durableSource =
                        if (!persisted && !isInherentlyDurable(parsed)) {
                            copyToPrivateStorage(parsed, metadata.extension)
                        } else {
                            DurableSource(parsed, fingerprint(parsed))
                        }
                    val durable = requireNotNull(durableSource)
                    source =
                        AudioSource(
                            id = IMPORTED_ID_PREFIX + UUID.randomUUID(),
                            uri = durable.uri.toString(),
                            displayName = metadata.displayName,
                            durationMs = metadata.durationMs,
                            mimeType = metadata.mimeType,
                        )
                    val record =
                        AudioSourceRecord(
                            source = requireNotNull(source),
                            accessStatus = SourceAccessStatus.AVAILABLE,
                            hasPersistedPermission = persisted,
                            contentFingerprint = durable.fingerprint,
                        )
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        store.upsert(record)
                        committed = true
                    }
                } catch (error: Throwable) {
                    if (!committed) {
                        withContext(NonCancellable) {
                            durableSource?.uri?.takeIf { it != parsed }?.let(::deletePrivateCopy)
                            if (persisted && !alreadyPersisted) releasePersistedPermission(parsed)
                        }
                    }
                    throw error
                }
                requireNotNull(source)
            }
        }

    override suspend fun relinkSource(
        sourceId: String,
        uri: String,
        takePersistablePermission: Boolean,
    ) = withContext(ioDispatcher) {
        sourceOperationMutex.withLock {
            val existing =
                store.get(sourceId) ?: throw failure(FailureCode.SOURCE_MISSING, "source_missing")
            val expectedFingerprint = existing.contentFingerprint
            val parsed = Uri.parse(uri)
            val metadata = readMetadata(parsed)
            require(
                existing.source.durationMs <= 0 ||
                    abs(existing.source.durationMs - metadata.durationMs) <= 1_000,
            ) { "relinked_source_duration_mismatch" }
            val candidateFingerprint = fingerprint(parsed)
            if (expectedFingerprint != null) {
                require(expectedFingerprint == candidateFingerprint) {
                    "relinked_source_content_mismatch"
                }
            }
            var persisted = false
            val alreadyPersisted =
                parsed.scheme == "content" &&
                    resolver.persistedUriPermissions.any { it.uri == parsed }
            if (takePersistablePermission &&
                parsed.scheme == "content"
            ) {
                persisted = tryPersistPermission(parsed)
            }
            var preparedSource: DurableSource? = null
            val durableSource =
                try {
                    if (!persisted && !isInherentlyDurable(parsed)) {
                        copyToPrivateStorage(parsed, metadata.extension).also { copied ->
                            preparedSource = copied
                            require(
                                expectedFingerprint == null ||
                                    copied.fingerprint == expectedFingerprint,
                            ) {
                                "relinked_source_content_mismatch"
                            }
                        }
                    } else {
                        DurableSource(parsed, candidateFingerprint)
                    }
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        preparedSource?.uri?.let(::deletePrivateCopy)
                        if (persisted && !alreadyPersisted) releasePersistedPermission(parsed)
                    }
                    throw error
                }
            val updatedSource =
                existing.source.copy(
                    uri = durableSource.uri.toString(),
                    displayName = metadata.displayName,
                    durationMs = metadata.durationMs,
                    mimeType = metadata.mimeType,
                )
            val replacement =
                existing.copy(
                    source = updatedSource,
                    accessStatus = SourceAccessStatus.AVAILABLE,
                    hasPersistedPermission = persisted,
                    contentFingerprint = durableSource.fingerprint,
                )
            var committed = false
            try {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    store.upsert(replacement)
                    committed = true
                }
            } catch (error: Throwable) {
                if (!committed) {
                    withContext(NonCancellable) {
                        val oldUri = Uri.parse(existing.source.uri)
                        if (durableSource.uri != oldUri) {
                            deletePrivateCopy(durableSource.uri)
                        }
                        if (persisted && !alreadyPersisted) {
                            releasePersistedPermission(replacement, durableSource.uri)
                        }
                    }
                }
                throw error
            }
            withContext(NonCancellable) {
                val oldUri = Uri.parse(existing.source.uri)
                if (oldUri != durableSource.uri) {
                    if (!store.hasPersistedUriOwner(sourceId, oldUri.toString())) {
                        releasePersistedPermission(existing, oldUri)
                    }
                    deletePrivateCopy(oldUri)
                }
            }
        }
    }

    override suspend fun removeSource(sourceId: String) =
        withContext(ioDispatcher) {
            sourceOperationMutex.withLock {
                val record = store.get(sourceId) ?: return@withContext
                // Room verifies that no job still owns this source before any external data or URI
                // permission is touched. A rejected deletion leaves both intact for resumption.
                val hasOtherPersistedOwner = store.remove(sourceId)
                val uri = Uri.parse(record.source.uri)
                if (!hasOtherPersistedOwner) releasePersistedPermission(record, uri)
                deletePrivateCopy(uri)
            }
        }

    private fun releasePersistedPermission(record: AudioSourceRecord, uri: Uri) {
        if (record.hasPersistedPermission && uri.scheme == "content") {
            releasePersistedPermission(uri)
        }
    }

    private fun releasePersistedPermission(uri: Uri) {
        if (uri.scheme == "content") {
            runCatching {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun queryMediaStore(): List<AudioSourceRecord> {
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        val projection =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
            )
        return try {
            resolver
                .query(
                    collection,
                    projection,
                    null,
                    null,
                    MediaStore.Audio.Media.DATE_ADDED + " DESC",
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameColumn =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media.DISPLAY_NAME,
                        )
                    val durationColumn =
                        cursor.getColumnIndexOrThrow(
                            MediaStore.Audio.Media.DURATION,
                        )
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val uri = ContentUris.withAppendedId(collection, id)
                            add(
                                AudioSourceRecord(
                                    AudioSource(
                                        id = "$MEDIA_ID_PREFIX$id",
                                        uri = uri.toString(),
                                        displayName =
                                            cursor.getString(nameColumn) ?: DEFAULT_DISPLAY_NAME,
                                        durationMs =
                                            cursor
                                                .getLong(
                                                    durationColumn,
                                                ).coerceAtLeast(0),
                                        mimeType =
                                            cursor.getString(
                                                mimeColumn,
                                            ) ?: DEFAULT_MIME_TYPE,
                                    ),
                                    SourceAccessStatus.AVAILABLE,
                                    hasPersistedPermission = false,
                                ),
                            )
                        }
                    }
                } ?: emptyList()
        } catch (security: SecurityException) {
            emptyList()
        }
    }

    private fun readMetadata(uri: Uri): SourceMetadata {
        var name = DEFAULT_DISPLAY_NAME
        var mime = resolver.getType(uri) ?: DEFAULT_MIME_TYPE
        if (uri.scheme == "content") {
            runCatching {
                resolver
                    .query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            name =
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME),
                                )
                                    ?: name
                        }
                    }
            }
        } else {
            name = uri.lastPathSegment?.takeIf(String::isNotBlank) ?: name
            mime = android.webkit.MimeTypeMap
                .getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: mime
        }
        val retriever = MediaMetadataRetriever()
        val duration =
            try {
                try {
                    retriever.setDataSource(applicationContext, uri)
                    retriever
                        .extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION,
                        )?.toLongOrNull()
                        ?: 0L
                } finally {
                    retriever.release()
                }
            } catch (security: SecurityException) {
                throw failure(
                    FailureCode.SOURCE_PERMISSION_REQUIRED,
                    "source_permission_required",
                    security,
                )
            } catch (error: RuntimeException) {
                throw failure(FailureCode.UNSUPPORTED_CODEC, "source_metadata_unavailable", error)
            }
        return SourceMetadata(
            name,
            duration.coerceAtLeast(0),
            mime,
            name.substringAfterLast('.', "bin"),
        )
    }

    private fun tryPersistPermission(uri: Uri): Boolean =
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }

    private fun isInherentlyDurable(uri: Uri): Boolean =
        uri.scheme == "file" || uri.authority == MediaStore.AUTHORITY

    private suspend fun copyToPrivateStorage(source: Uri, extension: String): DurableSource {
        privateImportDirectory.mkdirs()
        val target =
            File(
                privateImportDirectory,
                UUID.randomUUID().toString() + "." + extension.take(10),
            )
        var completed = false
        try {
            val durableSource =
                resolver.openInputStream(source)?.use { input ->
                    FileOutputStream(target).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > maximumPrivateImportBytes) {
                                throw failure(
                                    FailureCode.STORAGE_FULL,
                                    "private_import_size_limit",
                                )
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        output.fd.sync()
                        DurableSource(Uri.fromFile(target), digest.digest().toHexString())
                    }
                } ?: throw failure(FailureCode.SOURCE_MISSING, "source_open_failed")
            completed = true
            return durableSource
        } catch (error: AudioSourceException) {
            target.delete()
            throw error
        } catch (security: SecurityException) {
            target.delete()
            throw failure(
                FailureCode.SOURCE_PERMISSION_REQUIRED,
                "source_permission_required",
                security,
            )
        } catch (error: IOException) {
            target.delete()
            val code =
                if (applicationContext.filesDir.usableSpace < MINIMUM_FREE_BYTES) {
                    FailureCode.STORAGE_FULL
                } else {
                    FailureCode.SOURCE_MISSING
                }
            throw failure(code, "private_import_failed", error)
        } finally {
            if (!completed) target.delete()
        }
        error("private_copy_unreachable")
    }

    private suspend fun fingerprint(uri: Uri): String =
        try {
            resolver.openInputStream(uri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().toHexString()
            } ?: throw failure(FailureCode.SOURCE_MISSING, "source_open_failed")
        } catch (error: AudioSourceException) {
            throw error
        } catch (security: SecurityException) {
            throw failure(
                FailureCode.SOURCE_PERMISSION_REQUIRED,
                "source_permission_required",
                security,
            )
        } catch (error: IOException) {
            throw failure(FailureCode.SOURCE_MISSING, "source_fingerprint_failed", error)
        }

    private fun accessStatus(uriString: String): SourceAccessStatus {
        val uri = Uri.parse(uriString)
        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { }
            SourceAccessStatus.AVAILABLE
        } catch (_: SecurityException) {
            SourceAccessStatus.PERMISSION_REQUIRED
        } catch (_: IOException) {
            SourceAccessStatus.MISSING
        }
    }

    private fun deletePrivateCopy(uri: Uri) {
        if (uri.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull()
        if (parent ==
            runCatching { privateImportDirectory.canonicalFile }.getOrNull()
        ) {
            file.delete()
        }
    }

    private fun failure(code: FailureCode, diagnostic: String, cause: Throwable? = null) =
        AudioSourceException(DomainFailure(code, diagnostic), diagnostic, cause)

    private data class SourceMetadata(
        val displayName: String,
        val durationMs: Long,
        val mimeType: String,
        val extension: String,
    )

    private data class DurableSource(
        val uri: Uri,
        val fingerprint: String,
    )

    private companion object {
        const val PRIVATE_IMPORT_DIRECTORY = "imported-audio"
        const val IMPORTED_ID_PREFIX = "imported:"
        const val MEDIA_ID_PREFIX = "media:"
        const val DEFAULT_DISPLAY_NAME = "Imported audio"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val MINIMUM_FREE_BYTES = 1L * 1_024 * 1_024
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

private suspend fun <T> Flow<List<T>>.firstSnapshot(): List<T> = first()
