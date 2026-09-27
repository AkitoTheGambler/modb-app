package io.github.manamiproject.modb.app.convfiles

import io.github.manamiproject.AnimenewsnetworkAnimeConverter
import io.github.manamiproject.AnimenewsnetworkConfig
import io.github.manamiproject.modb.app.crawlers.anidb.AnidbSource
import io.github.manamiproject.modb.anidb.AnidbConfig
import io.github.manamiproject.modb.anilist.AnilistAnimeConverter
import io.github.manamiproject.modb.anilist.AnilistConfig
import io.github.manamiproject.modb.animeplanet.AnimePlanetAnimeConverter
import io.github.manamiproject.modb.animeplanet.AnimePlanetConfig
import io.github.manamiproject.modb.anisearch.AnisearchAnimeConverter
import io.github.manamiproject.modb.anisearch.AnisearchConfig
import io.github.manamiproject.modb.anisearch.AnisearchRelationsConfig
import io.github.manamiproject.modb.app.config.AppConfig
import io.github.manamiproject.modb.app.config.Config
import io.github.manamiproject.modb.app.crawlers.anidb.AnidbCrawler.Companion.ANIDB_PENDING_FILE_SUFFIX
import io.github.manamiproject.modb.app.downloadcontrolstate.DefaultDownloadControlStateUpdater.Companion.APP_NAMESPACE
import io.github.manamiproject.modb.core.config.ConfigRegistry
import io.github.manamiproject.modb.core.config.Hostname
import io.github.manamiproject.modb.core.config.DefaultConfigRegistry
import io.github.manamiproject.modb.core.config.IntPropertyDelegate
import io.github.manamiproject.modb.core.converter.DefaultPathAnimeConverter
import io.github.manamiproject.modb.core.coroutines.ModbDispatchers.LIMITED_CPU
import io.github.manamiproject.modb.core.coverage.KoverIgnore
import io.github.manamiproject.modb.core.excludeFromTestContext
import io.github.manamiproject.modb.core.extensions.EMPTY
import io.github.manamiproject.modb.core.extensions.fileName
import io.github.manamiproject.modb.core.extensions.fileSuffix
import io.github.manamiproject.modb.core.extensions.listRegularFiles
import io.github.manamiproject.modb.core.logging.LoggerDelegate
import io.github.manamiproject.modb.kitsu.KitsuAnimeConverter
import io.github.manamiproject.modb.kitsu.KitsuConfig
import io.github.manamiproject.modb.livechart.LivechartAnimeConverter
import io.github.manamiproject.modb.livechart.LivechartConfig
import io.github.manamiproject.modb.myanimelist.MyanimelistAnimeConverter
import io.github.manamiproject.modb.myanimelist.MyanimelistConfig
import io.github.manamiproject.modb.simkl.SimklAnimeConverter
import io.github.manamiproject.modb.simkl.SimklConfig
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.DurationUnit.SECONDS
import kotlin.time.toDuration

/**
 * Default implementation of [RawFileConversionService].
 * Uses [WatchService] implementations to constantly convert files as they are created.
 * @since 1.0.0
 * @param appConfig Application specific configuration. Uses [AppConfig] by default.
 * @param configRegistry Config registry used to load the waiting times.
 */
class DefaultRawFileConversionService(
    private val appConfig: Config = AppConfig.instance,
    configRegistry: ConfigRegistry = DefaultConfigRegistry.instance,
): RawFileConversionService {

    private val watchServices = CopyOnWriteArrayList(mutableListOf<WatchService>())

    private val conversionTimeoutInSeconds: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = APP_NAMESPACE,
        default = DEFAULT_CONVERSION_TIMEOUT_IN_SECONDS,
    )

    private val conversionStallChecks: Int by IntPropertyDelegate(
        configRegistry = configRegistry,
        namespace = APP_NAMESPACE,
        default = DEFAULT_CONVERSION_STALL_CHECKS,
    )

    override suspend fun unconvertedFilesExist(): Boolean = unconvertedFiles().isNotEmpty()

    override suspend fun waitForAllRawFilesToBeConverted() {
        var pending = unconvertedFiles()
        var checksWithoutProgress = 0

        withTimeoutOrNull(conversionTimeoutInSeconds.toDuration(SECONDS)) {
            while (pending.isNotEmpty() && checksWithoutProgress < conversionStallChecks) {
                val previous = pending.numberOfFiles()
                wait()
                pending = unconvertedFiles()

                checksWithoutProgress = if (pending.numberOfFiles() < previous) {
                    0
                } else {
                    checksWithoutProgress + 1
                }
            }
        }

        if (pending.isEmpty()) {
            return
        }

        log.warn { "Continuing with [${pending.numberOfFiles()}] raw files which could not be converted." }

        pending.forEach { (hostname, files) ->
            log.warn { "Unconverted for [$hostname]: ${files.sorted().take(MAX_REPORTED_FILES_PER_PROVIDER)}${if (files.size > MAX_REPORTED_FILES_PER_PROVIDER) " and [${files.size - MAX_REPORTED_FILES_PER_PROVIDER}] more" else EMPTY}" }
        }
    }

    private fun Map<Hostname, Set<String>>.numberOfFiles(): Int = this.values.sumOf { it.size }

    /**
     * Raw files which have no corresponding converted file, keyed by hostname. Counting raw files against converted
     * files in total cannot tell a file which is still being converted from one which never will be, so a single
     * unconvertible entry would keep the total apart forever.
     */
    private suspend fun unconvertedFiles(): Map<Hostname, Set<String>> {
        log.info { "Checking if there are still files which need to be converted." }

        return appConfig.metaDataProviderConfigurations().mapNotNull { config ->
            val files = appConfig.workingDir(config)
                .listRegularFiles()
                .filterNot { it.fileSuffix() == ANIDB_PENDING_FILE_SUFFIX }

            val converted = files.filter { it.fileSuffix() == CONVERTED_FILE_SUFFIX }
                .map { it.fileName().substringBeforeLast('.') }
                .toHashSet()

            val unconverted = files.filter { it.fileSuffix() == config.fileSuffix() }
                .map { it.fileName().substringBeforeLast('.') }
                .filterNot { it in converted }
                .toHashSet()

            if (unconverted.isEmpty()) null else config.hostname() to unconverted
        }.toMap()
    }

    @KoverIgnore
    private suspend fun wait() {
        excludeFromTestContext(appConfig) {
            delay(2.toDuration(SECONDS))
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun start(): Boolean {
        if (watchServices.isNotEmpty()) {
            log.info { "Skipping start, because watch services are already running. Either use shutdown before starting anew or create new instance of the class." }
            return false
        }

        log.info { "Starting watch services." }

        buildWatchServices()

        withContext(LIMITED_CPU) {
            watchServices.forEach { watchService ->
                watchService.prepare()
            }
            watchServices.forEach { watchService ->
                GlobalScope.launch {
                    watchService.watch()
                }
            }
        }

        return true
    }

    override suspend fun shutdown() {
        watchServices.forEach { it.stop() }
        watchServices.clear()
    }

    private fun buildWatchServices() {
        mapOf(
            AnidbSource.config() to AnidbSource.converter(),
            AnilistConfig to AnilistAnimeConverter.instance,
            AnimePlanetConfig to AnimePlanetAnimeConverter.instance,
            KitsuConfig to KitsuAnimeConverter.instance,
            LivechartConfig to LivechartAnimeConverter.instance,
            MyanimelistConfig to MyanimelistAnimeConverter.instance,
            SimklConfig to SimklAnimeConverter.instance,
            AnimenewsnetworkConfig to AnimenewsnetworkAnimeConverter.instance,
        ).map { entry ->
            SimpleConversionWatchService(
                appConfig = appConfig,
                metaDataProviderConfig = entry.key,
                converter = DefaultPathAnimeConverter(
                    animeConverter = entry.value,
                    fileSuffix = entry.key.fileSuffix(),
                )
            )
        }.forEach {
            watchServices.add(it)
        }

        watchServices.add(
            DependentConversionWatchService(
                appConfig = appConfig,
                mainConfig = AnisearchConfig,
                dependentMetaDataProviderConfigs = listOf(
                    AnisearchRelationsConfig,
                ),
                converter = DefaultPathAnimeConverter(
                    animeConverter = AnisearchAnimeConverter(
                        relationsDir = appConfig.workingDir(AnisearchRelationsConfig),
                    ),
                    fileSuffix = AnisearchConfig.fileSuffix(),
                ),
            )
        )
    }

    companion object {
        private val log by LoggerDelegate()

        private const val DEFAULT_CONVERSION_TIMEOUT_IN_SECONDS = 900
        private const val DEFAULT_CONVERSION_STALL_CHECKS = 30
        private const val MAX_REPORTED_FILES_PER_PROVIDER = 25

        /**
         * Singleton of [DefaultRawFileConversionService]
         * @since 1.0.0
         */
        val instance: DefaultRawFileConversionService by lazy { DefaultRawFileConversionService() }
    }
}