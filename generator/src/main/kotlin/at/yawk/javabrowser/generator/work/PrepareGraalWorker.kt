package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.MavenDependencyResolver
import at.yawk.javabrowser.generator.SourceSetConfig
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.common.hash.Hashing
import com.google.common.io.BaseEncoding
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.intellij.lang.annotations.Language
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact
import org.python.core.PyBoolean
import org.python.core.PyFloat
import org.python.core.PyInteger
import org.python.core.PyLong
import org.python.util.PythonInterpreter
import org.slf4j.LoggerFactory
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

private const val GRAAL_JAVA_VERSION = "java/11"

private val log = LoggerFactory.getLogger(PrepareGraalWorker::class.java)

class PrepareGraalWorker(
    private val tempDirProvider: TempDirProvider,
    private val mavenResolver: MavenDependencyResolver,
) : PrepareArtifactWorker<ArtifactConfig.Graal> {
    override fun getArtifactId(config: ArtifactConfig.Graal) = "graal/" + config.version

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun prepareArtifact(
        artifactId: String,
        config: ArtifactConfig.Graal,
        listener: PrepareArtifactWorker.PrepareListener,
    ) = tempDirProvider.withTempDir(artifactId) { tmp ->
        val suites = config.repos.flatMapIndexed { i, repo ->
            val repoPath = tmp.resolve(i.toString())
            PrepareAndroidWorker.downloadZip(repo, repoPath)
            getSuites(PrepareAndroidWorker.singleDescendant(repoPath))
        }

        val modules = suites.flatMap { (path, suite) -> findModules(path, suite) }
        val modulesById = HashMap<String, Module>()
        for (module in modules) {
            for (dependencyName in module.dependencyNames) {
                val prev = modulesById.put(dependencyName, module)
                if (prev != null) {
                    var identical = false
                    if (module is LibraryDependency && prev is LibraryDependency) {
                        // same maven coordinates
                        if (module.library.maven != null && module.library.maven == prev.library.maven) {
                            identical = true
                        }
                        // same sha1
                        if (module.library.sha1 == prev.library.sha1) {
                            identical = true
                        }
                    }
                    if (!identical) {
                        throw IllegalStateException("Duplicate dependency name $dependencyName: $module, $prev")
                    }
                }
            }
        }

        val dependencyArtifacts = ArrayList<String>()
        dependencyArtifacts.add(GRAAL_JAVA_VERSION)

        // download libraries
        for (module in modules) {
            if (module !is LibraryDependency) continue
            module.binaryPaths = emptyList() // replaced below if we can load the dep

            val library = module.library
            if (library.maven != null) {
                val transitiveDeps = mavenResolver.getMavenDependencies(
                    library.maven.groupId,
                    library.maven.artifactId,
                    library.maven.version
                )
                require(transitiveDeps.any {
                    it.coordinate.groupId == library.maven.groupId &&
                            it.coordinate.artifactId == library.maven.artifactId &&
                            it.coordinate.version == library.maven.version
                })
                module.binaryPaths = transitiveDeps.map { (it as MavenResolvedArtifact).asFile().toPath() }
                dependencyArtifacts.add("${library.maven.groupId}/${library.maven.artifactId}/${library.maven.version}")
            } else if (library.urls != null) {
                if (library.packedResource) continue
                var urls: List<String> = library.urls
                // replace {baseurl}, {version}, {host}, ...
                for ((k, v) in library.variables) {
                    if (v is String) {
                        urls = urls.map { it.replace("{${k}}", v) }
                    }
                }
                val singleUrl = urls.single()
                if (!singleUrl.endsWith(".jar")) continue
                val out = tmp.resolve("lib_${module.sanitizedName}.jar")
                log.info("Downloading {}", singleUrl)
                @Suppress("BlockingMethodInNonBlockingContext")
                Files.copy(URL(singleUrl).openStream(), out)
                @Suppress("BlockingMethodInNonBlockingContext", "UnstableApiUsage", "DEPRECATION")
                val actualHash = Hashing.sha1().newHasher().putBytes(Files.readAllBytes(out)).hash().asBytes()
                if (!actualHash.contentEquals(BaseEncoding.base16().decode(library.sha1!!))) {
                    throw RuntimeException("Checksum mismatch for $singleUrl: Expected ${library.sha1}")
                }
                module.binaryPaths = listOf(out)
            }
        }

        val jdkJmods = ArrayList<Path>()
        TarArchiveInputStream(GZIPInputStream(config.jdk.openStream())).use { s ->
            while (true) {
                val entry = s.nextTarEntry ?: break
                if (entry.name.endsWith(".jmod")) {
                    val nameEnd = entry.name.substring(entry.name.lastIndexOf('/') + 1)
                    val path = tmp.resolve(nameEnd)
                    Files.copy(s, path)
                    jdkJmods.add(path)
                }
            }
        }

        listener.acceptMetadata(
            PrepareArtifactWorker.Metadata(
                dependencyArtifactIds = dependencyArtifacts.distinct(),
                aliases = emptyList(),
                artifactMetadata = config.metadata
            )
        )

        val sortedModules = topologicalSort(modules, modulesById)

        // build transitive dependency lists
        for (sortedModule in sortedModules) {
            // this works because we're already in dependency order
            sortedModule.transitiveDependencies = (sortedModule.dependencies +
                    sortedModule.dependencies.mapNotNull { modulesById[it] }.flatMap { it.transitiveDependencies })
                .distinct()
        }

        // handle overlay paths. We just move all source files to the target module
        for (module in sortedModules) {
            if (module !is SourceModule) continue
            if (module.overlayTarget == null) continue
            val target = modulesById[module.overlayTarget] as SourceModule
            for (path in Files.walk(module.sourceDir)) {
                if (Files.isRegularFile(path)) {
                    val relative = module.sourceDir.relativize(path)
                    val dest = target.sourceDir.resolve(relative)
                    try {
                        Files.createDirectories(dest.parent)
                    } catch (e: FileAlreadyExistsException) {}
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        // finally, run actual compilation
        for (module in sortedModules) {
            if (module !is SourceModule) continue
            if (module.overlayTarget != null) continue // source files already moved, see above

            // skip modules w/o java files
            if (!Files.exists(module.sourceDir)) continue
            if (Files.walk(module.sourceDir).noneMatch { it.toString().endsWith(".java") }) continue

            val outDir = tmp.resolve("out").resolve(module.fullName)
            module.binaryPaths = listOf(outDir)
            listener.compileSourceSet(
                SourceSetConfig(
                    debugTag = "$artifactId/${module.fullName}",
                    sourceRoot = module.sourceDir,
                    dependencies = module.transitiveDependencies.mapNotNull { modulesById[it]?.binaryPaths }
                        .flatten() + jdkJmods,
                    pathPrefix = module.fullName + "/",
                    includeRunningVmBootclasspath = false,
                    outputClassesTo = outDir
                )
            )
        }
    }

    private fun topologicalSort(modules: List<Module>, modulesById: Map<String, Module>): List<Module> {
        // topological sort so that modules are built in proper order
        val sortedModules = ArrayList<Module>()
        fun requestModule(module: Module) {
            if (module !in sortedModules) {
                module.dependencies.forEach { depName ->
                    val depModule = modulesById[depName]
                    if (depModule == null) {
                        if (depName != "JVMCI_HOTSPOT") {
                            log.info("Missing dependency for ${module.primaryName}: $depName")
                        }
                    } else {
                        requestModule(depModule)
                    }
                }
                sortedModules.add(module)
            }
        }
        modules.forEach { requestModule(it) }
        return sortedModules
    }

    private fun getSuites(repoPath: Path): List<Pair<Path, Suite>> = sequence {
        for (rootModule in Files.list(repoPath)) {
            if (Files.isDirectory(rootModule)) {
                val suiteFileSub = rootModule.resolve("mx." + rootModule.fileName).resolve("suite.py")
                if (Files.exists(suiteFileSub)) {
                    val suite = parseSuite(Files.readAllBytes(suiteFileSub).toString(Charsets.UTF_8))
                    yield(Pair(rootModule, suite))
                }

                if (rootModule.fileName.toString().startsWith("mx.")) {
                    val suiteFileDirect = rootModule.resolve("suite.py")
                    if (Files.exists(suiteFileDirect)) {
                        val suite = parseSuite(Files.readAllBytes(suiteFileDirect).toString(Charsets.UTF_8))
                        yield(Pair(repoPath, suite))
                    }
                }
            }
        }
    }.toList()

    private fun findModules(suitePath: Path, suite: Suite): List<Module> {
        val modules = ArrayList<Module>()
        // source modules
        for ((projectId, project) in suite.projects) {
            if (project.subDir == null ||
                project.sourceDirs == null ||
                // skip modules with compliance 8, we compile on 11
                    project.compliance == "8") {
                modules.add(
                    VirtualModule(
                        suite.name,
                        projectId,
                        project.dependencies
                    )
                )
                continue
            }
            val sourceDir = suitePath
                .resolve(project.subDir)
                .resolve(projectId)
                // afaik the only source dir we care about is "src", but we check using .single in case any others appear
                .resolve(project.sourceDirs.single { it != "resources" && it != "headers" })
            modules.add(
                SourceModule(
                    suiteName = suite.name,
                    primaryName = projectId,
                    dependencies = project.dependencies,
                    sourceDir = sourceDir,
                    overlayTarget = project.overlayTarget
                )
            )
        }
        // distributions
        for ((id, distribution) in suite.distributions) {
            modules.add(
                VirtualModule(
                    suite.name,
                    id,
                    distribution.dependencies + distribution.distDependencies
                )
            )
        }
        // libraries
        for ((depId, library) in suite.libraries) {
            modules.add(LibraryDependency(suite.name, depId, library))
        }
        return modules
    }

    companion object {
        private val objectMapper = ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .registerKotlinModule()

        internal fun parseSuite(@Language("Python") pythonText: String): Suite {
            val json = PythonInterpreter().use { intr ->
                // TODO: isolate this?
                intr.exec(pythonText)
                val suiteObject = intr["suite"]
                toJson(suiteObject)
            }
            try {
                return objectMapper.convertValue(json, Suite::class.java)
            } catch (e: Exception) {
                throw RuntimeException("Failed to translate suite, data: $json", e)
            }
        }

        private fun toJson(pyObject: Any?): JsonNode = when (pyObject) {
            is Map<*, *> -> {
                val objectNode = JsonNodeFactory.instance.objectNode()
                pyObject.forEach { (k, v) ->
                    objectNode.set<JsonNode>(k as String, toJson(v))
                }
                objectNode
            }
            is List<*> -> {
                val arrayNode = JsonNodeFactory.instance.arrayNode()
                pyObject.forEach { arrayNode.add(toJson(it)) }
                arrayNode
            }
            is CharSequence -> JsonNodeFactory.instance.textNode(pyObject.toString())
            is Boolean -> JsonNodeFactory.instance.booleanNode(pyObject)
            is PyBoolean -> JsonNodeFactory.instance.booleanNode(pyObject.booleanValue)
            is Float -> JsonNodeFactory.instance.numberNode(pyObject)
            is Double -> JsonNodeFactory.instance.numberNode(pyObject)
            is PyFloat -> JsonNodeFactory.instance.numberNode(pyObject.value)
            is Int -> JsonNodeFactory.instance.numberNode(pyObject)
            is Long -> JsonNodeFactory.instance.numberNode(pyObject)
            is PyInteger -> JsonNodeFactory.instance.numberNode(pyObject.value)
            is PyLong -> JsonNodeFactory.instance.numberNode(pyObject.value)
            else -> throw UnsupportedOperationException("Unsupported python object type ${pyObject?.javaClass?.name}")
        }
    }

    internal data class Suite(
        val name: String,
        val libraries: Map<String, Library> = emptyMap(),
        val projects: Map<String, Project> = emptyMap(),
        val distributions: Map<String, Distribution> = emptyMap()
    ) {
        data class Project(
            val subDir: String? = null,
            val sourceDirs: List<String>? = null,
            val dependencies: List<String> = emptyList(),
            /**
             * Another project ID that may contain duplicate source files. This module's source files should take
             * priority. Used e.g. for JDK 11 support.
             */
            val overlayTarget: String? = null,
            val compliance: String? = null
        )

        data class Library(
            val sha1: String? = null,
            val urls: List<String>? = null,
            val maven: Maven? = null,
            val packedResource: Boolean = false,
            // has to be mutable
            @JsonAnySetter val variables: MutableMap<String, Any> = mutableMapOf()
        )

        data class Maven(
            val groupId: String,
            val artifactId: String,
            val version: String
        )

        data class Distribution(
            val dependencies: List<String> = emptyList(),
            val distDependencies: List<String> = emptyList()
        )
    }
}

private sealed class Module {
    abstract val suiteName: String
    abstract val primaryName: String
    abstract val dependencies: List<String>

    lateinit var transitiveDependencies: List<String>
    lateinit var binaryPaths: List<Path>

    /**
     * Names by which this module can be referred to
     */
    val dependencyNames: List<String>
        get() = listOf(primaryName, "$suiteName:$primaryName")

    val sanitizedName: String
        get() = primaryName.filter { (it in 'a'..'z') or (it in 'A'..'Z') or (it in '0'..'9') or (it == '.') }
}

/**
 * Module with no source files or additional data
 */
private data class VirtualModule(
    override val suiteName: String,
    override val primaryName: String,
    val components: List<String>
) : Module() {
    override val dependencies: List<String>
        get() = components

    init {
        binaryPaths = emptyList()
    }
}

private data class LibraryDependency(
    override val suiteName: String,
    override val primaryName: String,
    val library: PrepareGraalWorker.Suite.Library
) : Module() {
    override val dependencies: List<String>
        get() = emptyList()
}

private data class SourceModule(
    override val suiteName: String,
    override val primaryName: String,
    val sourceDir: Path,
    override val dependencies: List<String>,
    val overlayTarget: String?
) : Module() {
    val fullName = "$suiteName/$primaryName"
}