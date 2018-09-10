package at.yawk.javabrowser.server

import at.yawk.javabrowser.Printer
import at.yawk.javabrowser.SourceFileParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.io.MoreFiles
import org.jboss.shrinkwrap.resolver.api.maven.Maven
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType
import org.jboss.shrinkwrap.resolver.api.maven.ScopeType
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependencies
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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

class Compiler(private val dbi: DBI, private val objectMapper: ObjectMapper) {
    companion object {
        private val log = LoggerFactory.getLogger(Compiler::class.java)
        const val VERSION = 9
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

    private fun compileAndCommit(
            artifactId: String,
            sourceRoot: Path,
            dependencies: List<Path>,
            dependencyArtifactIds: List<String>,
            includeRunningVmBootclasspath: Boolean = true
    ) {
        DbPrinter.withPrinter(objectMapper, dbi, artifactId) { printer ->
            compile(artifactId, sourceRoot, dependencies, includeRunningVmBootclasspath, printer)
            dependencyArtifactIds.forEach { printer.addDependency(it) }
            if (includeRunningVmBootclasspath) {
                printer.addDependency("java/10")
            }
        }
    }

    fun compileOldJava(artifactId: String, artifact: ArtifactConfig.OldJava) {
        if (!needsRecompile(artifactId)) return
        tempDir { tmp ->
            val src = tmp.resolve("src")
            FileSystems.newFileSystem(artifact.src, null).use {
                val root = it.rootDirectories.single()
                copyDirectory(root, src)
            }

            compileAndCommit(artifactId,
                    src,
                    dependencies = emptyList(),
                    dependencyArtifactIds = emptyList(),
                    includeRunningVmBootclasspath = false)
        }
    }

    fun compileJava(artifactId: String, artifact: ArtifactConfig.Java) {
        if (!needsRecompile(artifactId)) return
        DbPrinter.withPrinter(objectMapper, dbi, artifactId) { printer ->
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

    private fun copyDirectory(src: Path, dest: Path): Path? {
        val norm = src.normalize()
        return Files.walkFileTree(norm, object : SimpleFileVisitor<Path>() {
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

    fun compileMaven(artifactId: String, artifact: ArtifactConfig.Maven) {
        if (!needsRecompile(artifactId)) return

        val depObjects = getMavenDependencies(artifact.groupId, artifact.artifactId, artifact.version)
                .filter {
                    it.coordinate.groupId != artifact.groupId &&
                            it.coordinate.artifactId != artifact.artifactId
                }
        val depPaths = depObjects.map { (it as MavenResolvedArtifact).asFile().toPath() }
        val depNames = depObjects.map {
            it.coordinate.groupId + "/" + it.coordinate.artifactId + "/" + it.coordinate.version
        }
        val sourceJar = Maven.resolver()
                .addDependency(MavenDependencies.createDependency(
                        MavenCoordinates.createCoordinate(
                                artifact.groupId, artifact.artifactId, artifact.version,
                                PackagingType.JAR, "sources"),
                        ScopeType.COMPILE,
                        false
                ))
                .resolve().withoutTransitivity().asSingleFile().toPath()
        tempDir { tmp ->
            val src = tmp.resolve("src")
            FileSystems.newFileSystem(sourceJar, null).use {
                val root = it.rootDirectories.single()
                copyDirectory(root, src)
            }
            compileAndCommit(artifactId, src, depPaths, depNames)
        }
    }
}