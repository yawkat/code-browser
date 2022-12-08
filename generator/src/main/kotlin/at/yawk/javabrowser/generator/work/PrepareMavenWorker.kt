package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.MavenDependencyResolver
import at.yawk.javabrowser.generator.SourceSetConfig
import com.google.common.annotations.VisibleForTesting
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.eclipse.aether.repository.RemoteRepository
import org.jboss.shrinkwrap.resolver.api.maven.Maven
import org.jboss.shrinkwrap.resolver.api.maven.MavenArtifactInfo
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType
import org.jboss.shrinkwrap.resolver.api.maven.ScopeType
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependencies
import org.jboss.shrinkwrap.resolver.impl.maven.bootstrap.MavenRepositorySystem
import org.jboss.shrinkwrap.resolver.impl.maven.bootstrap.MavenSettingsBuilder
import org.jboss.shrinkwrap.resolver.impl.maven.internal.MavenModelResolver
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Java version listed as a dependency for maven artifacts
 */
private const val NOMINAL_JAVA_VERSION = "java/11"

private val log = LoggerFactory.getLogger(PrepareMavenWorker::class.java)

private fun copyDirectory(src: Path, dest: Path) {
    val norm = src.normalize()
    Files.walkFileTree(norm, object : SimpleFileVisitor<Path>() {
        val visited = HashSet<Path>()

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!visited.add(dir)) {
                return FileVisitResult.SKIP_SUBTREE
            }
            Files.createDirectories(dest.resolve(norm.relativize(dir).toString()))
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            try {
                Files.copy(file, dest.resolve(norm.relativize(file).toString()))
            } catch (e: IOException) {
                log.error("Failed to copy $file", e)
                throw e
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            // happens for jruby: https://github.com/jruby/jruby/issues/5979
            if (exc is NoSuchFileException) {
                log.warn("Failed to visit $file for copying")
                return FileVisitResult.CONTINUE
            }
            return super.visitFileFailed(file, exc)
        }
    })
}

class PrepareMavenWorker(
    private val tempDirProvider: TempDirProvider,
    private val resolver: MavenDependencyResolver,
    parallelFetch: Int = 1
) : PrepareArtifactWorker<ArtifactConfig.Maven> {
    private val fetchSemaphore = Semaphore(parallelFetch)

    override fun getArtifactId(config: ArtifactConfig.Maven) =
        "${config.groupId}/${config.artifactId}/${config.version}"

    override suspend fun prepareArtifact(
        artifactId: String,
        config: ArtifactConfig.Maven,
        listener: PrepareArtifactWorker.PrepareListener
    ) {
        lateinit var depObjects: List<MavenArtifactInfo>
        lateinit var meta: ArtifactMetadata

        fetchSemaphore.withPermit {
            log.info("Fetching dependencies and metadata for {}", artifactId)
            depObjects = resolver.getMavenDependencies(
                config.groupId,
                config.artifactId,
                config.version
            )
                .filter {
                    it.coordinate.groupId != config.groupId ||
                            it.coordinate.artifactId != config.artifactId
                }
            meta = resolveMavenMetadata(config)
        }

        val depPaths = depObjects.map { (it as MavenResolvedArtifact).asFile().toPath() }
        val depNames = depObjects.map {
            var name = it.coordinate.groupId + "/" + it.coordinate.artifactId + "/" + it.coordinate.version
            if (!it.coordinate.classifier.isNullOrEmpty()) name += "/" + it.coordinate.classifier
            name
        }.toSet() // remove duplicates
        val aliasNames = config.aliases.map { getArtifactId(it) }

        listener.acceptMetadata(
            PrepareArtifactWorker.Metadata(
                dependencyArtifactIds = depNames + NOMINAL_JAVA_VERSION,
                aliases = aliasNames,
                artifactMetadata = meta
            )
        )

        val sourceJar = fetchSemaphore.withPermit {
            log.info("Fetching sources for {}", artifactId)
            Maven.resolver()
                .addDependency(
                    MavenDependencies.createDependency(
                        MavenCoordinates.createCoordinate(
                            config.groupId, config.artifactId, config.version,
                            PackagingType.JAR, "sources"
                        ),
                        ScopeType.COMPILE,
                        false
                    )
                )
                .resolve().withoutTransitivity()
                .asSingleFile().toPath()
        }

        tempDirProvider.withTempDir(artifactId) { tmp ->
            val src = tmp.resolve("src")
            FileSystems.newFileSystem(sourceJar, null as ClassLoader).use {
                val root = it.rootDirectories.single()
                copyDirectory(root, src)
            }
            listener.compileSourceSet(
                SourceSetConfig(
                    debugTag = artifactId,
                    sourceRoot = src,
                    dependencies = depPaths,
                    includeRunningVmBootclasspath = true
                )
            )
        }
    }

    companion object {
        @VisibleForTesting
        internal fun resolveMavenMetadata(artifact: ArtifactConfig.Maven): ArtifactMetadata {
            fun <E> List<E>.orNull() = if (isEmpty()) null else this

            // this code is so painful
            val jarPath = Maven.resolver()
                .addDependency(
                    MavenDependencies.createDependency(
                        MavenCoordinates.createCoordinate(
                            artifact.groupId, artifact.artifactId, artifact.version,
                            PackagingType.JAR, null
                        ),
                        ScopeType.COMPILE,
                        false
                    )
                )
                .resolve().withoutTransitivity()
                .asSingleFile().toPath()
            val pomPath = jarPath.parent.resolve(jarPath.fileName.toString().removeSuffix(".jar") + ".pom")
            val request = DefaultModelBuildingRequest()
            request.pomFile = pomPath.toFile()
            request.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            request.systemProperties = System.getProperties()
            val mavenRepositorySystem = MavenRepositorySystem()
            request.modelResolver = MavenModelResolver(
                mavenRepositorySystem, mavenRepositorySystem.getSession(
                    MavenSettingsBuilder().buildDefaultSettings(), false
                ),
                listOf(RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2").build())
            )
            val model = DefaultModelBuilderFactory().newInstance().build(request).effectiveModel

            val start = artifact.metadata ?: ArtifactMetadata()
            return start.copy(
                licenses = model.licenses.map { ArtifactMetadata.License(it.name, it.url) }.orNull()
                    ?: start.licenses,
                url = model.url ?: start.url,
                organization = model.organization?.let {
                    ArtifactMetadata.Organization(name = it.name, url = it.url)
                } ?: start.organization,
                contributors = model.contributors.map {
                    ArtifactMetadata.Developer(
                        name = it.name,
                        email = it.email,
                        url = it.url,
                        organization = ArtifactMetadata.Organization(
                            name = it.organization,
                            url = it.organizationUrl
                        )
                    )
                }.orNull() ?: start.contributors,
                description = model.description ?: start.description,
                developers = model.developers.map {
                    ArtifactMetadata.Developer(
                        name = it.name,
                        email = it.email,
                        url = it.url,
                        organization = ArtifactMetadata.Organization(
                            name = it.organization,
                            url = it.organizationUrl
                        )
                    )
                }.orNull() ?: start.developers,
                issueTracker = model.issueManagement?.let {
                    ArtifactMetadata.IssueTracker(type = it.system, url = it.url)
                } ?: start.issueTracker
            )
        }
    }
}