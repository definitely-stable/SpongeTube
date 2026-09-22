package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val activeWriterIds: MutableSet<ExtentId> =
        Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var closed = false

    var initialRecoveryReport: RecoveryReport = RecoveryReport.EMPTY
        private set

    suspend fun writeExtent(
        spec: ExtentSpec,
        producer: suspend ExtentSink.() -> Unit,
    ): CommittedExtent {
        var writer: ExtentWriter? = null
        var committed = false

        try {
            withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                writer = createWriter(spec)
            }

            currentCoroutineContext().ensureActive()
            val ownedWriter = checkNotNull(writer)
            producer(ownedWriter)
            currentCoroutineContext().ensureActive()

            val result = withContext(NonCancellable) {
                ownedWriter.commit()
            }
            committed = true
            return result
        } finally {
            val pendingWriter = writer
            if (!committed && pendingWriter != null) {
                withContext(NonCancellable) {
                    pendingWriter.abort()
                }
            }
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
                    "cannot close extent store while extent writes are active",
                )
            }

            closed = true
            metadataStore.close()
        }
    }

    private suspend fun createWriter(spec: ExtentSpec): ExtentWriter {
        ensureOpen()

        if (!activeWriterIds.add(spec.extentId)) {
            throw ExtentConflictException(
                "an extent write is already active for " + spec.extentId,
            )
        }

        var partFile: File? = null
        var output: FileOutputStream? = null

        try {
            layout.ensureRoot(durabilityOps)
            val finalRelativePath = layout.finalRelativePath(spec.extentId)

            metadataStore.assertWritable(
                spec = spec,
                storagePath = finalRelativePath,
            )

            val finalFile = layout.finalFile(spec.extentId, durabilityOps)
            if (finalFile.exists()) {
                throw ExtentConflictException(
                    "extent file already exists for " + spec.extentId,
                )
            }

            partFile = layout.createPartFile(
                spec.extentId,
                durabilityOps,
            )
            output = FileOutputStream(partFile, false)

            emit(
                ExtentLifecycleEvent(
                    extentId = spec.extentId,
                    state = ExtentLifecycleState.RECEIVING,
                ),
            )

            return ExtentWriter(
                store = this,
                spec = spec,
                partFile = partFile,
                finalFile = finalFile,
                output = output,
            )
        } catch (error: Throwable) {
            try {
                output?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }

            if (partFile?.exists() == true) {
                try {
                    durabilityOps.deleteDurably(partFile)
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
            }

            activeWriterIds.remove(spec.extentId)
            throw error
        }
    }

    internal suspend fun assertPublishable(extent: StoredExtent) {
        ensureOpen()
        metadataStore.assertPublishable(extent)
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
        ): ExtentStore {
            var metadataStore: RoomExtentMetadataStore? = null
            var store: ExtentStore? = null

            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()

                    val rootDirectory = File(context.filesDir, "sponge")
                    val durability = AndroidExtentDurabilityOps
                    val layout = ExtentPathLayout(rootDirectory)
                    layout.ensureRoot(durability)

                    val metadataDirectory = File(rootDirectory, "metadata")
                    durability.ensureDirectory(metadataDirectory)
                    val databaseFile = File(metadataDirectory, "extents.db")

                    val openedMetadata = RoomExtentMetadataStore.open(
                        context = context,
                        databaseFile = databaseFile,
                    )
                    metadataStore = openedMetadata

                    val openedStore = ExtentStore(
                        rootDirectory = rootDirectory,
                        metadataStore = openedMetadata,
                        durabilityOps = durability,
                        ioDispatcher = Dispatchers.IO,
                        lifecycleListener = lifecycleListener,
                        faultInjector = ExtentFaultInjector.NONE,
                    )
                    store = openedStore
                    openedStore.initialRecoveryReport =
                        openedStore.recoverInternal()
                }

                currentCoroutineContext().ensureActive()
                return checkNotNull(store)
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        val openedStore = store
                        if (openedStore != null) {
                            openedStore.close()
                        } else {
                            metadataStore?.close()
                        }
                    } catch (cleanupError: Throwable) {
                        error.addSuppressed(cleanupError)
                    }
                }
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
    )
