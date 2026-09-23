package io.github.definitelystable.spongetube.core.storage

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ExtentStore private constructor(
    rootDirectory: File,
    private val storeLease: ExtentStoreLease,
    private val metadataStore: ExtentMetadataStore,
    internal val durabilityOps: ExtentDurabilityOps,
    internal val ioDispatcher: CoroutineDispatcher,
    private val lifecycleListener: ExtentLifecycleListener?,
    internal val faultInjector: ExtentFaultInjector,
    val metadataDurability: ExtentMetadataDurability,
) : Closeable {
    internal val layout = ExtentPathLayout(rootDirectory)
    private val activeWriterIds = mutableSetOf<ExtentId>()
    private var activeReadOperations = 0

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

    suspend fun committedExtents(): List<CommittedExtent> {
        beginReadOperation()
        try {
            return withContext(ioDispatcher) {
                metadataStore.snapshot()
                    .asSequence()
                    .filter {
                        it.publicationState == ExtentPublicationState.PUBLISHED &&
                            it.integrityState == ExtentIntegrityState.VALID
                    }
                    .map(StoredExtent::toCommitted)
                    .toList()
            }
        } finally {
            endReadOperation()
        }
    }

    suspend fun openRead(
        extentId: ExtentId,
    ): ExtentReadHandle? {
        currentCoroutineContext().ensureActive()
        beginReadOperation()

        var openedHandle: ExtentReadHandle? = null
        var leaseReleased = false
        var ownershipTransferred = false

        try {
            withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                openedHandle = openReadInternal(extentId)
            }

            val handle = openedHandle
            if (handle == null) {
                leaseReleased = true
                endReadOperation()
            } else {
                ownershipTransferred = true
            }
            return handle
        } catch (error: Throwable) {
            if (!ownershipTransferred) {
                val handle = openedHandle
                if (handle != null) {
                    try {
                        handle.close()
                    } catch (cleanupError: Throwable) {
                        error.addSuppressed(cleanupError)
                    }
                } else if (!leaseReleased) {
                    leaseReleased = true
                    try {
                        endReadOperation()
                    } catch (cleanupError: Throwable) {
                        error.addSuppressed(cleanupError)
                    }
                }
            }
            throw error
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) {
                return
            }

            if (
                activeWriterIds.isNotEmpty() ||
                activeReadOperations != 0
            ) {
                throw ExtentConflictException(
                    "cannot close extent store while operations are active",
                )
            }

            closed = true
        }

        var failure: Throwable? = null

        try {
            metadataStore.close()
        } catch (error: Throwable) {
            failure = error
        }

        try {
            storeLease.close()
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure.addSuppressed(error)
            }
        }

        failure?.let {
            throw ExtentStoreException(
                "failed to close extent store",
                it,
            )
        }
    }

    private suspend fun openReadInternal(
        extentId: ExtentId,
    ): ExtentReadHandle? {
        val stored = metadataStore.extentById(extentId) ?: return null
        if (
            stored.publicationState != ExtentPublicationState.PUBLISHED ||
            stored.integrityState != ExtentIntegrityState.VALID
        ) {
            return null
        }

        val expectedPath = layout.finalRelativePath(extentId)
        if (stored.storagePath != expectedPath) {
            quarantineReadFailure(
                stored,
                ExtentQuarantineReason.UNEXPECTED_PATH,
            )
            return null
        }

        val file = try {
            layout.resolveStoredPath(stored.storagePath)
        } catch (_: IllegalArgumentException) {
            quarantineReadFailure(
                stored,
                ExtentQuarantineReason.UNEXPECTED_PATH,
            )
            return null
        }

        if (!file.exists()) {
            quarantineReadFailure(
                stored,
                ExtentQuarantineReason.MISSING_FILE,
            )
            return null
        }
        if (!file.isFile) {
            quarantineReadFailure(
                stored,
                ExtentQuarantineReason.UNEXPECTED_PATH,
            )
            return null
        }

        val channel = try {
            FileInputStream(file).channel
        } catch (error: IOException) {
            if (!file.exists()) {
                metadataStore.quarantine(
                    extentId,
                    ExtentQuarantineReason.MISSING_FILE,
                )
                return null
            }
            throw storageFailure(
                operation = "open-extent-read",
                cause = error,
            )
        }

        val persistedLength = try {
            channel.size()
        } catch (error: IOException) {
            runCatching { channel.close() }
            throw storageFailure(
                operation = "stat-extent-read",
                cause = error,
            )
        }

        if (persistedLength != stored.length) {
            runCatching { channel.close() }
            quarantineReadFailure(
                stored,
                ExtentQuarantineReason.LENGTH_MISMATCH,
            )
            return null
        }

        return ExtentReadHandle(
            extent = stored.toCommitted(),
            channel = channel,
            onClosed = ::endReadOperation,
        )
    }

    private suspend fun createWriter(spec: ExtentSpec): ExtentWriter {
        reserveWriter(spec.extentId)

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
            output = try {
                FileOutputStream(partFile, false)
            } catch (error: IOException) {
                throw storageFailure(
                    operation = "open-extent-temp",
                    cause = error,
                )
            }

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
            val failure = if (
                error is IOException &&
                error !is ExtentStoreException
            ) {
                storageFailure(
                    operation = "create-extent-temp",
                    cause = error,
                )
            } else {
                error
            }

            try {
                output?.close()
            } catch (closeError: Throwable) {
                failure.addSuppressed(closeError)
            }

            if (partFile?.exists() == true) {
                try {
                    durabilityOps.deleteDurably(partFile)
                } catch (cleanupError: Throwable) {
                    failure.addSuppressed(cleanupError)
                }
            }

            releaseWriter(spec.extentId)
            throw failure
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

    private fun reserveWriter(extentId: ExtentId) {
        synchronized(this) {
            ensureOpen()
            if (!activeWriterIds.add(extentId)) {
                throw ExtentConflictException(
                    "an extent write is already active for " + extentId,
                )
            }
        }
    }

    internal fun releaseWriter(extentId: ExtentId) {
        synchronized(this) {
            check(activeWriterIds.remove(extentId)) {
                "extent writer reservation was not active: " + extentId
            }
        }
    }

    private fun beginReadOperation() {
        synchronized(this) {
            ensureOpen()
            activeReadOperations += 1
        }
    }

    private fun endReadOperation() {
        synchronized(this) {
            check(activeReadOperations > 0) {
                "extent read operation counter underflow"
            }
            activeReadOperations -= 1
        }
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

    private suspend fun quarantineReadFailure(
        row: StoredExtent,
        reason: ExtentQuarantineReason,
    ) {
        metadataStore.quarantine(row.extentId, reason)
        deleteRowFileIfExpected(row)
    }

    private fun validationFailure(
        row: StoredExtent,
    ): ExtentQuarantineReason? {
        val file = try {
            layout.resolveStoredPath(row.storagePath)
        } catch (_: IllegalArgumentException) {
            return ExtentQuarantineReason.UNEXPECTED_PATH
        }

        val fact = try {
            FileIntegrity.inspect(file)
        } catch (error: IOException) {
            throw storageFailure(
                operation = "verify-published-extent",
                cause = error,
            )
        }
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
            var storeLease: ExtentStoreLease? = null
            var metadataStore: RoomExtentMetadataStore? = null
            var store: ExtentStore? = null

            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()

                    val rootDirectory = File(context.filesDir, "sponge")
                    val durability = AndroidExtentDurabilityOps
                    val layout = ExtentPathLayout(rootDirectory)
                    layout.ensureRoot(durability)

                    val acquiredLease =
                        FileExtentStoreLease.acquire(rootDirectory)
                    storeLease = acquiredLease

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
                        storeLease = acquiredLease,
                        metadataStore = openedMetadata,
                        durabilityOps = durability,
                        ioDispatcher = Dispatchers.IO,
                        lifecycleListener = lifecycleListener,
                        faultInjector = ExtentFaultInjector.NONE,
                        metadataDurability = openedMetadata.durability,
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
                            storeLease?.close()
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
                storeLease = NoOpExtentStoreLease,
                metadataStore = metadataStore,
                durabilityOps = durabilityOps,
                ioDispatcher = ioDispatcher,
                lifecycleListener = null,
                faultInjector = faultInjector,
                metadataDurability = ExtentMetadataDurability.UNVERIFIED_TEST,
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

            val storeLease = FileExtentStoreLease.acquire(rootDirectory)
            var metadataStore: RoomExtentMetadataStore? = null
            var store: ExtentStore? = null

            try {
                databaseFile.parentFile?.let(durability::ensureDirectory)

                val openedMetadata = RoomExtentMetadataStore.open(
                    context = context,
                    databaseFile = databaseFile,
                )
                metadataStore = openedMetadata

                val openedStore = ExtentStore(
                    rootDirectory = rootDirectory,
                    storeLease = storeLease,
                    metadataStore = openedMetadata,
                    durabilityOps = durability,
                    ioDispatcher = Dispatchers.IO,
                    lifecycleListener = null,
                    faultInjector = ExtentFaultInjector.NONE,
                    metadataDurability = openedMetadata.durability,
                )
                store = openedStore
                openedStore.initialRecoveryReport =
                    openedStore.recoverInternal()
                openedStore
            } catch (error: Throwable) {
                try {
                    val openedStore = store
                    if (openedStore != null) {
                        openedStore.close()
                    } else {
                        metadataStore?.close()
                        storeLease.close()
                    }
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
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
