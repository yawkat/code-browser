package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.SourceSetConfig
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.InvalidExitValueException
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream
import java.io.IOException
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import kotlin.streams.toList

private const val ANDROID_JAVA_VERSION = "java/8"

private val log = LoggerFactory.getLogger(PrepareAndroidWorker::class.java)

class PrepareAndroidWorker(
    private val tempDirProvider: TempDirProvider
) : PrepareArtifactWorker<ArtifactConfig.Android> {
    override fun getArtifactId(config: ArtifactConfig.Android) = "android/${config.version}"

    override suspend fun prepareArtifact(
        artifactId: String,
        config: ArtifactConfig.Android,
        listener: PrepareArtifactWorker.PrepareListener
    ) {
        listener.acceptMetadata(
            PrepareArtifactWorker.Metadata(
                dependencyArtifactIds = listOf(ANDROID_JAVA_VERSION),
                aliases = emptyList(),
                artifactMetadata = config.metadata
            )
        )

        tempDirProvider.withTempDir(artifactId) { tmp ->
            val outDebug = Slf4jStream.of(Compiler::class.java).asDebug()
            val outWarn = Slf4jStream.of(Compiler::class.java).asWarn()
            val repoBaseDir = tmp.resolve("repo")
            for ((i, repo) in config.repos.withIndex()) {
                log.info("Cloning ${repo.url}...")
                ProcessExecutor()
                    .command(
                        "git", "-c", "advice.detachedHead=false", "clone", "-q", "--depth=1", "--single-branch",
                        "--branch=${repo.tag}",
                        repo.url.toString(),
                        repoBaseDir.resolve("$i").toString()
                    )
                    .exitValueNormal()
                    .redirectOutput(outDebug)
                    .redirectError(outWarn)
                    .execute()
            }
            // collect source directories
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

            // copy source directories and collect aidl files
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

            log.info("Downloading build tools for $artifactId from ${config.buildTools}")
            val buildToolsDir = tmp.resolve("build-tools")
            downloadZip(config.buildTools, buildToolsDir)
            val aidlPath = singleDescendant(buildToolsDir).resolve("aidl")

            Files.setPosixFilePermissions(
                aidlPath, setOf(
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ
                )
            )

            log.debug("Compiling AIDL")
            for (path in aidl) {
                val executor = ProcessExecutor()
                    .command(
                        aidlPath.toString(),
                        "-I$combined",
                        path.toString()
                    )
                    .redirectOutput(outDebug)
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

            listener.compileSourceSet(
                SourceSetConfig(
                    artifactId,
                    sourceRoot = combined,
                    dependencies = emptyList(),
                    includeRunningVmBootclasspath = true
                )
            )
        }
    }

    companion object {
        internal fun downloadZip(url: URL, dir: Path) {
            ZipInputStream(url.openStream()).use {
                while (true) {
                    val entry = it.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val dest = dir.resolve(entry.name).normalize()
                    if (!dest.startsWith(dir)) throw AssertionError()
                    try {
                        Files.createDirectories(dest.parent)
                    } catch (ignored: FileAlreadyExistsException) {
                    }
                    Files.copy(it, dest)
                }
            }
        }

        internal fun singleDescendant(path: Path): Path =
            Files.list(path).toList().singleOrNull()?.let { singleDescendant(it) } ?: path
    }
}