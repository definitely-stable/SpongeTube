package io.github.definitelystable.spongetube.playback.baseline

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
internal object BaselineCacheStore {

    private var cache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @WorkerThread
    @Synchronized
    fun prepare(
        context: Context,
        cacheState: BaselineCacheState,
    ): BaselineCacheSnapshot {
        require(
            cacheState == BaselineCacheState.COLD ||
                cacheState == BaselineCacheState.WARM,
        ) {
            "Standard cache preparation requires COLD or WARM state"
        }

        val simpleCache = cache ?: createCache(context.applicationContext)

        if (cacheState == BaselineCacheState.COLD) {
            simpleCache.keys.toList().forEach(simpleCache::removeResource)
        }

        return BaselineCacheSnapshot(
            cache = simpleCache,
            bytesAtPreparation = simpleCache.cacheSpace,
        )
    }

    @Synchronized
    fun cacheBytes(): Long = cache?.cacheSpace ?: 0L

    private fun createCache(context: Context): SimpleCache {
        val provider = StandaloneDatabaseProvider(context)
        val directory = File(context.cacheDir, BaselineCacheContract.DIRECTORY_NAME)
        val created = SimpleCache(
            directory,
            LeastRecentlyUsedCacheEvictor(BaselineCacheContract.QUOTA_BYTES),
            provider,
        )

        databaseProvider = provider
        cache = created
        return created
    }
}

@UnstableApi
internal data class BaselineCacheSnapshot(
    val cache: SimpleCache,
    val bytesAtPreparation: Long,
)
