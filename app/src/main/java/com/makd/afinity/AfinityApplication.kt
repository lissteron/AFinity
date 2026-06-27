package com.makd.afinity

import android.app.Application
import android.os.Process
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.makd.afinity.cast.CastManager
import com.makd.afinity.data.local.LocalLibraryMediaRepository
import com.makd.afinity.data.local.LocalLibraryScanService
import com.makd.afinity.data.local.LocalLibraryVisibilityContext
import com.makd.afinity.data.repository.download.DownloadQueuePolicyCoordinator
import com.makd.afinity.data.repository.download.DownloadQueueMigration
import com.makd.afinity.data.repository.download.SchedulerLivenessCoordinator
import com.makd.afinity.data.repository.PreferencesRepository
import com.makd.afinity.data.updater.UpdateScheduler
import com.makd.afinity.di.ImageClient
import com.makd.afinity.util.logging.CrashFileExporter
import com.makd.afinity.util.logging.RingBufferTree
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class AfinityApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var updateScheduler: UpdateScheduler

    @Inject lateinit var preferencesRepository: PreferencesRepository

    @Inject lateinit var castManager: CastManager

    @Inject @ImageClient lateinit var imageOkHttpClient: OkHttpClient

    @Inject lateinit var downloadQueueMigration: DownloadQueueMigration

    @Inject lateinit var downloadQueuePolicyCoordinator: DownloadQueuePolicyCoordinator

    @Inject lateinit var schedulerLivenessCoordinator: SchedulerLivenessCoordinator

    @Inject lateinit var localLibraryMediaRepository: LocalLibraryMediaRepository

    @Inject lateinit var localLibraryScanService: LocalLibraryScanService

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var ringBufferTree: RingBufferTree? = null
        private set

    @Volatile private var imageCacheEnabled: Boolean = true
    @Volatile private var imageCacheSizeMb: Int = 512

    override fun onCreate() {
        super.onCreate()

        applicationScope.launch(Dispatchers.IO) {
            Timber.d("ImageLoader prefs: reading from DataStore")
            imageCacheEnabled = preferencesRepository.getImageCacheEnabled()
            imageCacheSizeMb = preferencesRepository.getImageCacheSizeMb()
            Timber.d("ImageLoader prefs: cacheEnabled=$imageCacheEnabled, cacheSizeMb=$imageCacheSizeMb")
        }

        ringBufferTree = RingBufferTree()
        Timber.plant(ringBufferTree!!)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.i(
                "Afinity Application started: uid=%d user=%s version=%s/%d",
                Process.myUid(),
                Process.myUserHandle(),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            )
        }

        Thread.setDefaultUncaughtExceptionHandler(
            CrashFileExporter(this, ringBufferTree, Thread.getDefaultUncaughtExceptionHandler())
        )

        castManager.initialize(this)

        applicationScope.launch {
            updateScheduler.cancelUpdateChecks()
            Timber.d("Automatic update checks disabled")
        }

        applicationScope.launch(Dispatchers.IO) {
            downloadQueueMigration.run()
            downloadQueuePolicyCoordinator.start()
            schedulerLivenessCoordinator.start()
        }

        applicationScope.launch(Dispatchers.IO) {
            try {
                val hasIndexedMedia = localLibraryMediaRepository.hasIndexedMedia()
                val hasMissingArtwork = localLibraryMediaRepository.hasIndexedMediaMissingArtwork()
                val refreshableArtworkCount =
                    if (hasIndexedMedia) {
                        localLibraryScanService.refreshableLocalLibraryArtworkCount()
                    } else {
                        0
                    }
                val needsIndexRebuild = !hasIndexedMedia
                if (!needsIndexRebuild && !hasMissingArtwork && refreshableArtworkCount == 0) {
                    return@launch
                }
                Timber.d(
                    "Repairing local library index in background: rebuild=%s, missingArtwork=%s, refreshableArtwork=%d",
                    needsIndexRebuild,
                    hasMissingArtwork,
                    refreshableArtworkCount,
                )
                localLibraryScanService.scanEnabledRootsWithArtworkBackfill(
                    LocalLibraryVisibilityContext(
                        currentUserId = preferencesRepository.getCurrentUserId(),
                        kidModeEnabled = false,
                        parentUnlocked = false,
                    )
                )
            } catch (error: Exception) {
                Timber.w(error, "Failed to backfill local library artwork index")
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val isCacheEnabled = imageCacheEnabled
        val cacheSizeMb = imageCacheSizeMb
        Timber.d("ImageLoader: creating singleton (cacheEnabled=$isCacheEnabled, cacheSizeMb=$cacheSizeMb)")

        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
                add(SvgDecoder.Factory())
                add(AnimatedImageDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCachePolicy(if (isCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(cacheSizeMb * 1024L * 1024L)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }
}
