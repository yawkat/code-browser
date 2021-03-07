package at.yawk.javabrowser.generator

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.generator.db.ActorAsyncTransactionProvider
import at.yawk.javabrowser.generator.db.FullUpdateStrategy
import at.yawk.javabrowser.generator.db.InPlaceUpdateStrategy
import at.yawk.javabrowser.generator.db.LimitedTransactionProvider
import at.yawk.javabrowser.generator.db.TransactionProvider
import at.yawk.javabrowser.generator.db.UpdateStrategy
import at.yawk.javabrowser.generator.work.CompileWorker
import at.yawk.javabrowser.generator.work.PrepareAndroidWorker
import at.yawk.javabrowser.generator.work.PrepareArtifactWorker
import at.yawk.javabrowser.generator.work.PrepareJdkWorker
import at.yawk.javabrowser.generator.work.PrepareMavenWorker
import at.yawk.javabrowser.generator.work.TempDirProvider
import com.google.common.collect.HashMultiset
import com.google.common.io.MoreFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger("at.yawk.javabrowser.generator.Generator")

const val COMPILER_VERSION = 42

private class CombinedPrepareWorker(
    tempDirProvider: TempDirProvider,
    config: Config
) : PrepareArtifactWorker<ArtifactConfig> {
    private val jdk = PrepareJdkWorker(tempDirProvider)
    private val android = PrepareAndroidWorker(tempDirProvider)
    private val maven = PrepareMavenWorker(tempDirProvider, MavenDependencyResolver(config.mavenResolver))

    override fun getArtifactId(config: ArtifactConfig) = when (config) {
        is ArtifactConfig.Android -> android.getArtifactId(config)
        is ArtifactConfig.Java -> jdk.getArtifactId(config)
        is ArtifactConfig.Maven -> maven.getArtifactId(config)
    }

    override suspend fun prepareArtifact(
        artifactId: String,
        config: ArtifactConfig,
        listener: PrepareArtifactWorker.PrepareListener
    ) = when (config) {
        is ArtifactConfig.Android -> android.prepareArtifact(artifactId, config, listener)
        is ArtifactConfig.Java -> jdk.prepareArtifact(artifactId, config, listener)
        is ArtifactConfig.Maven -> maven.prepareArtifact(artifactId, config, listener)
    }
}

fun main(args: Array<String>) {
    val config = Config.fromFile(Paths.get(args[0]))

    val duplicateArtifactBag = HashMultiset.create(config.artifacts)
    if (duplicateArtifactBag.entrySet().any { it.count > 1 }) {
        log.error("Duplicate artifacts: ${duplicateArtifactBag.entrySet().filter { it.count > 1 }.map { it.element }}")
        return
    }

    val base = Paths.get("/var/tmp/code-browser-generator")
    try {
        @Suppress("UnstableApiUsage")
        MoreFiles.deleteDirectoryContents(base)
    } catch (ignored: NoSuchFileException) {
    }
    Files.createDirectories(base)
    val tempDirProvider = TempDirProvider.Limited(
        TempDirProvider.FromDirectory(base)
    )
    val prepareWorker = CombinedPrepareWorker(tempDirProvider, config)

    val artifacts = config.artifacts.associateBy { prepareWorker.getArtifactId(it) }

    val dbi = config.database.start(mode = DbConfig.Mode.GENERATOR)

    // select update strategy

    val upToDate: List<String>
    val updateStrategy: UpdateStrategy

    val inPlace = InPlaceUpdateStrategy(dbi)
    if (inPlace.hasSchema()) {
        // old `data` schema exists, check that
        val upToDateInPlace = inPlace.listUpToDate()
        if (upToDateInPlace.size > artifacts.size * 0.6) {
            log.info("Performing incremental update")
            // incremental update
            updateStrategy = inPlace
            upToDate = upToDateInPlace
            updateStrategy.prepare()
        } else {
            log.info("Performing full update (too many changes)")
            // too many changes, fall back to full update
            updateStrategy = FullUpdateStrategy(dbi)
            updateStrategy.prepare()
            upToDate = updateStrategy.listUpToDate()
        }
    } else {
        log.info("Performing full update (new database)")
        // no data schema yet, fall back to full update
        updateStrategy = FullUpdateStrategy(dbi)
        updateStrategy.prepare()
        upToDate = updateStrategy.listUpToDate()
    }

    var transactionProvider: TransactionProvider = updateStrategy
    transactionProvider = LimitedTransactionProvider(transactionProvider)

    val dbWorkerThread = CoroutineScope(
        Executors.newSingleThreadExecutor(PrefixThreadFactory("db"))
            .asCoroutineDispatcher()
    )
    transactionProvider = ActorAsyncTransactionProvider(transactionProvider, dbWorkerThread)

    val pushWorker = CompileWorker(
        acceptScope = CoroutineScope(
            Executors.newFixedThreadPool(8, PrefixThreadFactory("accept")).asCoroutineDispatcher()
        ),
        transactionProvider = transactionProvider
    )

    // perform compilation

    val prepareScope = CoroutineScope(
        Executors.newFixedThreadPool(8, PrefixThreadFactory("prepare")).asCoroutineDispatcher()
    )
    val futures = artifacts.filter { it.key !in upToDate }.toList().map { (artifactId, config) ->
        prepareScope.async {
            pushWorker.forArtifact(artifactId) { forArtifact ->
                prepareWorker.prepareArtifact(artifactId, config, forArtifact)
            }
        }
    }
    runBlocking {
        futures.awaitAll()
    }
    updateStrategy.finish(artifacts.keys)
}

private class PrefixThreadFactory(private val prefix: String) : ThreadFactory {
    private val counter = AtomicInteger(1)

    override fun newThread(r: Runnable): Thread {
        val thread = Thread(r, "$prefix-${counter.getAndIncrement()}")
        thread.isDaemon = true
        return thread
    }
}