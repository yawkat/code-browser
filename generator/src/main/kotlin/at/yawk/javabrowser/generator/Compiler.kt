package at.yawk.javabrowser.generator

import at.yawk.javabrowser.ArtifactMetadata
import com.google.common.annotations.VisibleForTesting
import com.google.common.io.MoreFiles
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.eclipse.aether.repository.RemoteRepository
import org.jboss.shrinkwrap.resolver.api.maven.Maven
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType
import org.jboss.shrinkwrap.resolver.api.maven.ScopeType
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependencies
import org.jboss.shrinkwrap.resolver.impl.maven.bootstrap.MavenRepositorySystem
import org.jboss.shrinkwrap.resolver.impl.maven.bootstrap.MavenSettingsBuilder
import org.jboss.shrinkwrap.resolver.impl.maven.internal.MavenModelResolver
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.InvalidExitValueException
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern
import java.util.zip.ZipFile

/**
 * @author yawkat
 */

private inline fun tempDir(f: (Path) -> Unit) {
    val tmp = Files.createTempDirectory("compile")
    try {
        f(tmp)
    } finally {
        MoreFiles.deleteRecursively(tmp)
    }
}

class Compiler(
        private val dbi: DBI,
        private val session: Session,
        private val mavenDependencyResolver: MavenDependencyResolver = MavenDependencyResolver()
) {
    companion object {
        private val log = LoggerFactory.getLogger(Compiler::class.java)

        /**
         * Java version listed as a dependency for maven artifacts
         */
        private const val NOMINAL_JAVA_VERSION = "java/11"
        private const val ANDROID_JAVA_VERSION = "java/8"

        const val VERSION = 31
    }

    private fun needsRecompile(artifactId: String): Boolean {
        return dbi.inTransaction { conn: Handle, _ ->
            val present = conn.select("select lastCompileVersion from artifacts where id = ?", artifactId)
            if (present.isEmpty()) {
                true
            } else {
                (present.single()["lastCompileVersion"] as Number).toInt() < VERSION
            }
        }
    }

    private fun compile(
            artifactId: String,
            sourceRoot: Path,
            dependencies: List<Path>,
            includeRunningVmBootclasspath: Boolean,
            printer: Printer,
            pathPrefix: String = ""
    ) {
        log.info("Compiling $artifactId at $sourceRoot with dependencies $dependencies (boot=$includeRunningVmBootclasspath prefix=$pathPrefix)")

        val parser = SourceFileParser(sourceRoot, printer)
        parser.includeRunningVmBootclasspath = includeRunningVmBootclasspath
        parser.pathPrefix = pathPrefix
        parser.dependencies = dependencies
        parser.compile()
    }

    fun compileOldJava(artifactId: String, artifact: ArtifactConfig.OldJava) {
        if (!needsRecompile(artifactId)) return
        session.withPrinter(artifactId, artifact.metadata) { printer ->
            tempDir { tmp ->
                val src = tmp.resolve("src")
                FileSystems.newFileSystem(artifact.src, null).use {
                    val root = it.rootDirectories.single()
                    copyDirectory(root, src)
                }

                compile(artifactId,
                        src,
                        dependencies = emptyList(),
                        includeRunningVmBootclasspath = false,
                        printer = printer)
            }
        }
    }

    fun compileJava(artifactId: String, artifact: ArtifactConfig.Java) {
        if (!needsRecompile(artifactId)) return
        session.withPrinter(artifactId, artifact.metadata) { printer ->
            tempDir { tmp ->
                val jmodClassCache = tmp.resolve("jmodClassCache")
                Files.list(artifact.baseDir.resolve("jmods")).iterator().forEach {
                    ZipFile(it.toFile()).use { zipFile ->
                        val entries = zipFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith("classes/")) {
                                val target = jmodClassCache.resolve(entry.name.removePrefix("classes/"))
                                Files.createDirectories(target.parent)
                                zipFile.getInputStream(entry).use {
                                    Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                                }
                            }
                        }
                    }
                }

                FileSystems.newFileSystem(artifact.baseDir.resolve("lib/src.zip"), null).use {
                    Files.list(it.rootDirectories.single()).iterator().forEach { moduleDir ->
                        val moduleName = moduleDir.fileName.toString().removeSuffix("/")
                        val src = tmp.resolve("src-$moduleName")
                        copyDirectory(moduleDir, src)

                        compile(artifactId,
                                src,
                                dependencies = listOf(jmodClassCache),
                                includeRunningVmBootclasspath = false,
                                pathPrefix = "$moduleName/",
                                printer = printer)

                        MoreFiles.deleteRecursively(src)
                    }
                }
            }
        }
    }

    fun compileAndroid(artifactId: String, artifact: ArtifactConfig.Android) {
        if (!needsRecompile(artifactId)) return
        session.withPrinter(artifactId, artifact.metadata) { printer ->
            tempDir { tmp ->
                val outInfo = Slf4jStream.of(Compiler::class.java).asInfo()
                val repoBaseDir = tmp.resolve("repo")
                for ((i, repo) in artifact.repos.withIndex()) {
                    log.info("Cloning ${repo.url}...")
                    ProcessExecutor()
                            .command("git", "clone", "--depth=1", "--single-branch",
                                    "--branch=${repo.tag}",
                                    repo.url.toString(),
                                    repoBaseDir.resolve("$i").toString())
                            .exitValueNormal()
                            .redirectOutput(outInfo)
                            .redirectError(outInfo)
                            .execute()
                }
                val collected = ArrayList<Path>()
                fun visit(path: Path) {
                    val java = path.resolve("java")
                    if (Files.exists(java)) {
                        collected.add(java)
                        return
                    }
                    // aidl defs
                    val binder = path.resolve("binder")
                    if (Files.exists(binder)) {
                        collected.add(binder)
                        return
                    }
                    val src = path.resolve("src")
                    if (Files.exists(src)) {
                        val srcMainJava = src.resolve("main/java")
                        if (Files.exists(srcMainJava)) {
                            collected.add(srcMainJava)
                        } else {
                            collected.add(src)
                        }
                        return
                    }
                    for (child in Files.list(path)) {
                        val name = child.fileName.toString()
                        if (name.contains("test")) {
                            continue
                        }
                        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                            continue
                        }
                        visit(child)
                    }
                }
                visit(repoBaseDir)
                log.info("Source folders for $artifactId: $collected")

                val combined = tmp.resolve("combined")
                val aidl = ArrayList<Path>()
                for (path in collected) {
                    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            Files.createDirectories(combined.resolve(path.relativize(dir).toString()))
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            try {
                                val isAidl = file.toString().toLowerCase().endsWith(".aidl")
                                if (file.toString().toLowerCase().endsWith(".java") || isAidl) {
                                    val dest = combined.resolve(path.relativize(file).toString())
                                    Files.copy(file, dest)
                                    if (isAidl) {
                                        aidl.add(dest)
                                    }
                                }
                            } catch (e: IOException) {
                                log.error("Failed to copy $file", e)
                                throw e
                            }
                            return FileVisitResult.CONTINUE
                        }
                    })
                }

                log.debug("Compiling AIDL")
                for (path in aidl) {
                    val executor = ProcessExecutor()
                            .command(System.getProperty("android.aidl-path", "aidl"),
                                    "-I$combined",
                                    path.toString())
                            .redirectOutput(outInfo)
                            .redirectErrorStream(true)
                            .readOutput(true)
                            .exitValueAny()
                    val result = executor.execute()
                    // some aidl files seem to be missing. create them on the fly :(
                    if (result.exitValue == 1) {
                        val matcher = Pattern.compile("couldn't find import for class ([\\w.]+)")
                                .matcher(result.output.string)
                        var retry = false
                        while (matcher.find()) {
                            retry = true
                            val qualifiedName = matcher.group(1)
                            val splitPoint = qualifiedName.lastIndexOf('.')
                            val pkg = qualifiedName.substring(0, splitPoint)
                            val dir = combined.resolve(pkg.replace('.', '/'))
                            if (!Files.isDirectory(dir)) {
                                Files.createDirectories(dir)
                            }
                            val name = qualifiedName.substring(splitPoint + 1)
                            val file = dir.resolve("$name.aidl")
                            Files.write(file, "package $pkg;\nparcelable $name;".toByteArray())
                        }
                        if (retry) {
                            executor.exitValueNormal().execute()
                        } else {
                            throw InvalidExitValueException("Exit value 1", result)
                        }
                    }
                }

                compile(
                        artifactId,
                        combined,
                        dependencies = emptyList(),
                        includeRunningVmBootclasspath = true,
                        printer = printer
                )
                printer.addDependency(ANDROID_JAVA_VERSION)
            }
        }
    }

    private fun copyDirectory(src: Path, dest: Path) {
        val norm = src.normalize()
        Files.walkFileTree(norm, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
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
        })
    }

    @VisibleForTesting
    internal fun resolveMavenMetadata(artifact: ArtifactConfig.Maven): ArtifactMetadata {
        fun <E> List<E>.orNull() = if (isEmpty()) null else this

        // this code is so painful
        val jarPath = Maven.resolver()
                .addDependency(MavenDependencies.createDependency(
                        MavenCoordinates.createCoordinate(
                                artifact.groupId, artifact.artifactId, artifact.version,
                                PackagingType.JAR, null),
                        ScopeType.COMPILE,
                        false
                ))
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
                MavenSettingsBuilder().buildDefaultSettings(), false),
                listOf(RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2").build()))
        val model = DefaultModelBuilderFactory().newInstance().build(request).effectiveModel

        val start = artifact.metadata ?: ArtifactMetadata()
        return start.copy(
                licenses = model.licenses.map { ArtifactMetadata.License(it.name, it.url) }.orNull() ?: start.licenses,
                url = model.url ?: start.url,
                organization = model.organization?.let {
                    ArtifactMetadata.Organization(name = it.name, url = it.url)
                } ?: start.organization,
                contributors = model.contributors.map {
                    ArtifactMetadata.Developer(name = it.name,
                            email = it.email,
                            url = it.url,
                            organization = ArtifactMetadata.Organization(name = it.organization,
                                    url = it.organizationUrl))
                }.orNull() ?: start.contributors,
                description = model.description ?: start.description,
                developers = model.developers.map {
                    ArtifactMetadata.Developer(name = it.name,
                            email = it.email,
                            url = it.url,
                            organization = ArtifactMetadata.Organization(name = it.organization,
                                    url = it.organizationUrl))
                }.orNull() ?: start.developers,
                issueTracker = model.issueManagement?.let {
                    ArtifactMetadata.IssueTracker(type = it.system, url = it.url)
                } ?: start.issueTracker
        )
    }

    fun compileMaven(artifactId: String, artifact: ArtifactConfig.Maven) {
        if (!needsRecompile(artifactId)) return

        val depObjects = mavenDependencyResolver.getMavenDependencies(artifact.groupId,
                artifact.artifactId,
                artifact.version)
                .filter {
                    it.coordinate.groupId != artifact.groupId ||
                            it.coordinate.artifactId != artifact.artifactId
                }
        val depPaths = depObjects.map { (it as MavenResolvedArtifact).asFile().toPath() }
        val depNames = depObjects.map {
            var name = it.coordinate.groupId + "/" + it.coordinate.artifactId + "/" + it.coordinate.version
            if (it.coordinate.classifier != null) name += "/" + it.coordinate.classifier
            name
        }
        val metadata = resolveMavenMetadata(artifact)
        val sourceJar = Maven.resolver()
                .addDependency(MavenDependencies.createDependency(
                        MavenCoordinates.createCoordinate(
                                artifact.groupId, artifact.artifactId, artifact.version,
                                PackagingType.JAR, "sources"),
                        ScopeType.COMPILE,
                        false
                ))
                .resolve().withoutTransitivity()
                .asSingleFile().toPath()
        session.withPrinter(artifactId, metadata) { printer ->
            tempDir { tmp ->
                val src = tmp.resolve("src")
                FileSystems.newFileSystem(sourceJar, null).use {
                    val root = it.rootDirectories.single()
                    copyDirectory(root, src)
                }
                compile(artifactId, src, depPaths, true, printer)
                depNames.forEach { printer.addDependency(it) }
                printer.addDependency(NOMINAL_JAVA_VERSION)
            }
        }
    }
}