package io.github.definitelystable.spongetube.core.storage

data class ExtentMetadataDurability(
    val journalMode: String,
    val synchronous: Int,
    val busyTimeoutMs: Long,
) {
    val isPowerLossHardened: Boolean
        get() =
            journalMode.equals("truncate", ignoreCase = true) &&
                synchronous >= SQLITE_SYNCHRONOUS_FULL

    private companion object {
        const val SQLITE_SYNCHRONOUS_FULL = 2
    }
}

class ExtentMetadataDurabilityException internal constructor(
    val observed: ExtentMetadataDurability,
) : ExtentStoreException(
    "metadata durability policy mismatch: " +
        "journalMode=${observed.journalMode} " +
        "synchronous=${observed.synchronous} " +
        "busyTimeoutMs=${observed.busyTimeoutMs}",
)
