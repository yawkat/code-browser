package at.yawk.javabrowser.server

import at.yawk.javabrowser.Printer
import at.yawk.javabrowser.SourceFileParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.io.MoreFiles
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.jboss.shrinkwrap.resolver.api.maven.Maven
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType
import org.jboss.shrinkwrap.resolver.api.maven.ScopeType
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependencies
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.TransactionStatus
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors

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
        const val VERSION = 1
    }

    fun needsRecompile(artifactId: String): Boolean {
        return dbi.inTransaction { conn: Handle, _ ->
            val present = conn.select("select lastCompileVersion from artifacts where id = ?", artifactId)
            if (present.isEmpty()) {
                true
            } else {
                (present.single()["lastCompileVersion"] as Number).toInt() < VERSION
            }
        }
    }

    fun compile(
            artifactId: String,
            sourceRoot: Path,
            dependencies: List<Path>,
            includeRunningVmBootclasspath: Boolean = true
    ) {
        log.info("Compiling $artifactId at $sourceRoot with dependencies $dependencies (boot=$includeRunningVmBootclasspath)")

        val parser = ASTParser.newParser(AST.JLS10)
        parser.setCompilerOptions(mapOf(
                JavaCore.COMPILER_SOURCE to JavaCore.VERSION_10,
                JavaCore.CORE_ENCODING to "UTF-8",
                JavaCore.COMPILER_DOC_COMMENT_SUPPORT to JavaCore.ENABLED
        ))
        parser.setResolveBindings(true)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setEnvironment(
                dependencies.map { it.toString() }.toTypedArray(),
                arrayOf(sourceRoot.toString()),
                arrayOf("UTF-8"),
                includeRunningVmBootclasspath)


        val files = Files.walk(sourceRoot)
                .filter { it.toString().endsWith(".java") }
                .collect(Collectors.toList())

        val printer = Printer()

        parser.createASTs(
                files.map { it.toString() }.toTypedArray(),
                files.map { "UTF-8" }.toTypedArray(),
                emptyArray<String>(),
                SourceFileParser.Requestor(sourceRoot, printer),
                null
        )

        dbi.inTransaction { conn: Handle, _: TransactionStatus ->
            conn.update("delete from bindings where artifactId = ?", artifactId)
            conn.update("delete from sourceFiles where artifactId = ?", artifactId)
            conn.update("delete from artifacts where id = ?", artifactId)
            conn.insert("insert into artifacts (id, lastCompileVersion) values (?, ?)", artifactId, VERSION)
            for ((path, file) in printer.sourceFiles) {
                conn.insert(
                        "insert into sourceFiles (artifactId, path, json) VALUES (?, ?, ?)",
                        artifactId,
                        path,
                        objectMapper.writeValueAsBytes(file))
            }
            for ((binding, sourceFile) in printer.bindings) {
                conn.insert(
                        "insert into bindings (artifactId, binding, sourceFile, isType) VALUES (?, ?, ?, ?)",
                        artifactId,
                        binding,
                        sourceFile,
                        printer.types.contains(binding)
                )
            }
        }
    }

    fun compileOldJava(artifact: Artifact.OldJava) {
        val artifactId = "java/${artifact.version}"
        if (!needsRecompile(artifactId)) return
        tempDir { tmp ->
            val src = tmp.resolve("src")
            FileSystems.newFileSystem(artifact.src, null).use {
                val root = it.rootDirectories.single()
                copyDirectory(root, src)
            }

            compile(artifactId, src, dependencies = emptyList(), includeRunningVmBootclasspath = false)
        }
    }

    fun compileJava(artifact: Artifact.Java) {
        val artifactId = "java.base/${artifact.version}"
        if (!needsRecompile(artifactId)) return
        tempDir { tmp ->
            val src = tmp.resolve("src")
            // TODO: other modules than java.base
            FileSystems.newFileSystem(artifact.src, null).use {
                val root = it.rootDirectories.single().resolve("java.base")
                copyDirectory(root, src)
            }

            compile(artifactId,
                    src,
                    dependencies = emptyList(),
                    includeRunningVmBootclasspath = false)
        }
    }

    private fun copyDirectory(src: Path, dest: Path): Path? {
        return Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(dest.resolve(src.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, dest.resolve(src.relativize(file).toString()))
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun compileMaven(artifact: Artifact.Maven) {
        for (version in artifact.versions) {
            val artifactId = "${artifact.groupId}/${artifact.artifactId}/$version"
            if (!needsRecompile(artifactId)) return

            val dependencies = getMavenDependencies(artifact.groupId, artifact.artifactId, version)
                    .filter {
                        it.coordinate.groupId != artifact.groupId &&
                                it.coordinate.artifactId != artifact.artifactId
                    }
                    .map { (it as MavenResolvedArtifact).asFile().toPath() }
            val sourceJar = Maven.resolver()
                    .addDependency(MavenDependencies.createDependency(
                            MavenCoordinates.createCoordinate(
                                    artifact.groupId, artifact.artifactId, version,
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
                compile(artifactId, src, dependencies)
            }
        }
    }
}