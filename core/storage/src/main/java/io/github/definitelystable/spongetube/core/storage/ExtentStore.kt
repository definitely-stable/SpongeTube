package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExtentStore private constructor(
    rootDirectory: File,
    private val metadataStore: ExtentMetadataStore,
    internal val durabilityOps: ExtentDurabilityOps,
    internal val ioDispatcher: CoroutineDispatcher,
    private val lifecycleListener: ExtentLifecycleListener?,
    internal val faultInjector: ExtentFaultInjector,
) : Closeable {
    internal val layout = ExtentPathLayout(rootDirectory)
    private val activeWriterIds: MutableSet<ExtentId> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var closed = false

    var initialRecoveryReport: RecoveryReport = RecoveryReport.EMPTY
        private set

    suspend fun openWriter(spec: ExtentSpec): ExtentWriter =
        withContext(ioDispatcher) {
            ensureOpen()

            if (!activeWriterIds.add(spec.extentId)) {
                throw ExtentConflictException(
                    "an extent writer is already active for " + spec.extentId,
                )
            }

            try {
                layout.ensureRoot(durabilityOps)
                val finalFile = layout.finalFile(spec.extentId, durabilityOps)
                if (finalFile.exists()) {
                    throw ExtentConflictException(
                        "extent file already exists for " + spec.extentId,
                    )
                }

                val partFile = layout.createPartFile(
                    spec.extentId,
                    durabilityOps,
                )
                val output = FileOutputStream(partFile, false)

                emit(
                    ExtentLifecycleEvent(
                        extentId = spec.extentId,
                        state = ExtentLifecycleState.RECEIVING,
                    ),
                )

                ExtentWriter(
                    store = this@ExtentStore,
                    spec = spec,
                    partFile = partFile,
                    finalFile = finalFile,
                    output = output,
                )
            } catch (error: Throwable) {
                activeWriterIds.remove(spec.extentId)
                throw error
            }
        }

    suspend fun committedExtents(): List<CommittedExtent> =
        withContext(ioDispatcher) {
            ensureOpen()
            metadataStore.snapshot()
                .asSequence()
                .filter {
                    it.publicationState == ExtentPublicationState.PUBLISHED &&
                        it.integrityState == ExtentIntegrityState.VALID
                }
                .map(StoredExtent::toCommitted)
                .toList()
        }

    override fun close() {
        synchronized(this) {
            if (closed) {
                return
            }

            if (activeWriterIds.isNotEmpty()) {
                throw ExtentConflictException(
                    "cannot close extent store while writers are active",
                )
            }

            closed = true
            metadataStore.close()
        }
    }

    internal suspend fun publish(extent: StoredExtent) {
        ensureOpen()
        metadataStore.publish(extent)
    }

    internal fun releaseWriter(extentId: ExtentId) {
        activeWriterIds.remove(extentId)
    }

    internal fun emit(event: ExtentLifecycleEvent) {
        val listener = lifecycleListener ?: return
        runCatching {
            listener.onEvent(event)
        }
    }

    internal fun hit(point: ExtentFaultPoint) {
        faultInjector.hit(point)
    }

    private fun ensureOpen() {
        if (closed) {
            throw ExtentClosedException()
        }
    }

    private suspend fun recoverInternal(): RecoveryReport {
        layout.ensureRoot(durabilityOps)

        var deletedParts = 0
        var deletedOrphans = 0
        var deletedQuarantined = 0
        var quarantined = 0
        var verifiedPublished = 0

        for (partFile in layout.partFiles()) {
            durabilityOps.deleteDurably(partFile)
            deletedParts += 1
        }

        val rows = metadataStore.snapshot()
        val validPublishedPaths = mutableSetOf<String>()

        for (row in rows) {
            if (row.publicationState == ExtentPublicationState.QUARANTINED) {
                deleteRowFileIfExpected(row)?.let {
                    deletedQuarantined += 1
                }
                continue
            }

            val expectedPath = layout.finalRelativePath(row.extentId)
            val reason = when {
                row.storagePath != expectedPath ->
                    ExtentQuarantineReason.UNEXPECTED_PATH
                else -> validationFailure(row)
            }

            if (reason == null) {
                validPublishedPaths += row.storagePath
                verifiedPublished += 1
                continue
            }

            metadataStore.quarantine(row.extentId, reason)
            quarantined += 1
            deleteRowFileIfExpected(row)?.let {
                deletedQuarantined += 1
            }
        }

        for (finalFile in layout.finalExtentFiles()) {
            val relativePath = layout.relativePath(finalFile)
            if (relativePath !in validPublishedPaths) {
                durabilityOps.deleteDurably(finalFile)
                deletedOrphans += 1
            }
        }

        return RecoveryReport(
            deletedPartFiles = deletedParts,
            deletedOrphanFiles = deletedOrphans,
            deletedQuarantinedFiles = deletedQuarantined,
            quarantinedExtents = quarantined,
            verifiedPublishedExtents = verifiedPublished,
        )
    }

    private fun validationFailure(
        row: StoredExtent,
    ): ExtentQuarantineReason? {
        val file = try {
            layout.resolveStoredPath(row.storagePath)
        } catch (_: IllegalArgumentException) {
            return ExtentQuarantineReason.UNEXPECTED_PATH
        }

        val fact = FileIntegrity.inspect(file)
        return when {
            !fact.exists -> ExtentQuarantineReason.MISSING_FILE
            fact.length != row.length ->
                ExtentQuarantineReason.LENGTH_MISMATCH
            fact.sha256 != row.sha256 ->
                ExtentQuarantineReason.SHA256_MISMATCH
            else -> null
        }
    }

    private fun deleteRowFileIfExpected(
        row: StoredExtent,
    ): Unit? {
        val expectedPath = layout.finalRelativePath(row.extentId)
        if (row.storagePath != expectedPath) {
            return null
        }

        val file = runCatching {
            layout.resolveStoredPath(row.storagePath)
        }.getOrNull() ?: return null

        if (!file.exists()) {
            return null
        }

        durabilityOps.deleteDurably(file)
        return Unit
    }

    companion object {
        suspend fun open(
            context: Context,
            lifecycleListener: ExtentLifecycleListener? = null,
        ): ExtentStore = withContext(Dispatchers.IO) {
            val rootDirectory = File(context.filesDir, "sponge")
            val durability = AndroidExtentDurabilityOps
            val layout = ExtentPathLayout(rootDirectory)
            layout.ensureRoot(durability)

            val metadataDirectory = File(rootDirectory, "metadata")
            durability.ensureDirectory(metadataDirectory)
            val databaseFile = File(metadataDirectory, "extents.db")
            val metadataStore = RoomExtentMetadataStore.open(
                context = context,
                databaseFile = databaseFile,
            )

            val store = ExtentStore(
                rootDirectory = rootDirectory,
                metadataStore = metadataStore,
                durabilityOps = durability,
                ioDispatcher = Dispatchers.IO,
                lifecycleListener = lifecycleListener,
                faultInjector = ExtentFaultInjector.NONE,
            )

            try {
                store.initialRecoveryReport = store.recoverInternal()
                store
            } catch (error: Throwable) {
                metadataStore.close()
                throw error
            }
        }

        internal suspend fun openForTest(
            rootDirectory: File,
            metadataStore: ExtentMetadataStore,
            durabilityOps: ExtentDurabilityOps,
            ioDispatcher: CoroutineDispatcher,
            faultInjector: ExtentFaultInjector = ExtentFaultInjector.NONE,
        ): ExtentStore = withContext(ioDispatcher) {
            val store = ExtentStore(
                rootDirectory = rootDirectory,
                metadataStore = metadataStore,
                durabilityOps = durabilityOps,
                ioDispatcher = ioDispatcher,
                lifecycleListener = null,
                faultInjector = faultInjector,
            )
            store.initialRecoveryReport = store.recoverInternal()
            store
        }

        internal suspend fun openAndroidForTest(
            context: Context,
            rootDirectory: File,
            databaseFile: File,
        ): ExtentStore = withContext(Dispatchers.IO) {
            val durability = AndroidExtentDurabilityOps
            val layout = ExtentPathLayout(rootDirectory)
            layout.ensureRoot(durability)
            databaseFile.parentFile?.let(durability::ensureDirectory)

            val metadataStore = RoomExtentMetadataStore.open(
                context = context,
                databaseFile = databaseFile,
            )

            val store = ExtentStore(
                rootDirectory = rootDirectory,
                metadataStore = metadataStore,
                durabilityOps = durability,
                ioDispatcher = Dispatchers.IO,
                lifecycleListener = null,
                faultInjector = ExtentFaultInjector.NONE,
            )

            try {
                store.initialRecoveryReport = store.recoverInternal()
                store
            } catch (error: Throwable) {
                metadataStore.close()
                throw error
            }
        }
    }
}

private fun StoredExtent.toCommitted(): CommittedExtent =
    CommittedExtent(
        extentId = extentId,
        trackId = trackId,
        representationId = representationId,
        mediaStartUs = mediaStartUs,
        mediaEndUs = mediaEndUs,
        byteStart = byteStart,
        byteEndExclusive = byteEndExclusive,
        dependencyExtentIds = dependencyExtentIds,
        length = length,
        sha256 = sha256,
        storagePath = storagePath,
    )
