package at.yawk.javabrowser.generator.artifact

import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.PrinterWithDependencies
import org.slf4j.LoggerFactory
import org.zeroturnaround.exec.InvalidExitValueException
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream
import java.io.IOException
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

private val log = LoggerFactory.getLogger("at.yawk.javabrowser.generator.artifact.android")

fun compileAndroid(
        printer: PrinterWithDependencies,
        artifactId: String,
        artifact: ArtifactConfig.Android
) {
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
        log.info("Source folders for $artifactId: $collected")

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

        log.info("Downloading build tools for $artifactId from ${artifact.buildTools}")
        val buildToolsDir = tmp.resolve("build-tools")
        ZipInputStream(artifact.buildTools.openStream()).use {
            while (true) {
                val entry = it.nextEntry ?: break
                if (entry.isDirectory) continue
                val dest = buildToolsDir.resolve(entry.name).normalize()
                if (!dest.startsWith(buildToolsDir)) throw AssertionError()
                try {
                    Files.createDirectories(dest.parent)
                } catch (ignored: FileAlreadyExistsException) {}
                Files.copy(it, dest)
            }
        }
        var aidlPath = buildToolsDir
        while (true) {
            val direct = aidlPath.resolve("aidl")
            if (Files.exists(direct)) {
                aidlPath = direct
                break
            } else {
                // descend one directory level, but only if there is only a single directory. else error
                aidlPath = Files.list(aidlPath).toList().single()
            }
        }

        Files.setPosixFilePermissions(aidlPath, setOf(
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_READ))

        log.debug("Compiling AIDL")
        for (path in aidl) {
            val executor = ProcessExecutor()
                    .command(aidlPath.toString(),
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