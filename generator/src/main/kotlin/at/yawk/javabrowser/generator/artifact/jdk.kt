package at.yawk.javabrowser.generator.artifact

import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.Printer
import at.yawk.javabrowser.generator.SourceFileParser
import at.yawk.javabrowser.generator.getRequiredModules
import at.yawk.javabrowser.generator.parseModuleFile
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

private class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val stream: InputStream
)

private fun loadArchive(archiveUrl: URL, handleEntry: (ArchiveEntry) -> Unit) {
    if (archiveUrl.file.endsWith(".zst")) {
        TarArchiveInputStream(ZstdInputStream(ResumingUrlInputStream(archiveUrl).buffered()).buffered()).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                handleEntry(ArchiveEntry(entry.name, entry.isDirectory, tar))
            }
        }
    } else if (archiveUrl.file.endsWith(".zip")) {
        ZipInputStream(ResumingUrlInputStream(archiveUrl).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                handleEntry(ArchiveEntry(entry.name, entry.isDirectory, zip))
            }
        }
    } else {
        throw IllegalArgumentException("Unknown file extension: $archiveUrl")
    }
}

suspend fun compileJdk(
        printer: Printer,
        artifactId: String,
        artifact: ArtifactConfig.Java
) {
    tempDir { tmp ->

        val modules = HashSet<String>()
        // jigsaw 9: jdk-updates_jdk9u/{jdk,jaxp}/src/*/{share,solaris}/classes/*
        // jigsaw 10+: jdk-updates_jdk10u/src/*/{share,solaris}/classes/*
        // old: jdk6_jdk6/jdk/src/{share,solaris}/classes/*
        // platforms at low indices in this list will overwrite files from high indices
        val platforms = listOf("share", "linux", "unix", "solaris", "macosx", "windows", "aix")
        val platformGroup = "(?<platform>" + platforms.joinToString("|") + ")"
        val moduleGroup = "(?<module>[a-z.]+)"
        val namePatterns =
            if (artifact.jigsaw)
                listOf(
                    Pattern.compile("^/?[\\w-]+/(\\w+/)?src/$moduleGroup/$platformGroup/classes/(?<path>.+)$"),
                    Pattern.compile("^/?[\\w-]+/build/$platformGroup-[\\w-]+/support/gensrc/$moduleGroup/(?<path>.+)$")
                )
            else
                listOf(
                    Pattern.compile("^/?\\w+/jdk/src/$platformGroup/classes/(?<path>.+)$"),
                    Pattern.compile("^/?\\w+/build/$platformGroup-\\w+/(jdk/)?gensrc/(?<path>.+)$")
                )
        val bestPlatform = HashMap<String, String>()
        loadArchive(artifact.archiveUrl) { entry ->
            if (entry.isDirectory) return@loadArchive
            val matcher = namePatterns.map { it.matcher(entry.name) }.firstOrNull { it.matches() }
                ?: return@loadArchive
            val module = if (artifact.jigsaw) matcher.group("module") else null
            val platform = matcher.group("platform")!!
            val path = matcher.group("path")!!
            val outPath = if (module == null) path else "$module/$path"
            val oldPlatform = bestPlatform[outPath]
            if (oldPlatform == platform) throw AssertionError("Duplicate file: ${entry.name}")
            if (oldPlatform == null || platforms.indexOf(oldPlatform) > platforms.indexOf(platform)) {
                val dest = tmp.resolve(outPath).normalize()
                if (!dest.startsWith(tmp)) throw AssertionError("Bad path: ${entry.name}")
                try {
                    Files.createDirectories(dest.parent)
                } catch (ignored: FileAlreadyExistsException) {
                }
                Files.copy(entry.stream, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            if (module != null) {
                modules.add(module)
            }
        }

        if (artifact.jigsaw) {
            val dependencies = modules.mapNotNull { module ->
                val moduleInfo = tmp.resolve(module).resolve("module-info.java")
                if (!Files.exists(moduleInfo)) return@mapNotNull null
                var deps = getRequiredModules(parseModuleFile(moduleInfo))
                if (module != "java.base") deps += "java.base"
                module to deps
            }.toMap()

            val sortedModules = ArrayList<String>()
            fun requestModule(module: String) {
                if (module !in sortedModules) {
                    dependencies.getValue(module).forEach { requestModule(it) }
                    sortedModules.add(module)
                }
            }
            dependencies.keys.forEach { requestModule(it) }

            fun binaryRoot(module: String) = tmp.resolve("bin").resolve(module)

            for (module in sortedModules) {
                val sourceRoot = tmp.resolve(module)
                val moduleDeps = dependencies.getValue(module)

                val parser = SourceFileParser(sourceRoot, printer)
                // in java.base there are odd circular dependencies in the codegen so we use the vm boot cp
                parser.includeRunningVmBootclasspath = false
                parser.pathPrefix = "$module/"
                parser.dependencies = moduleDeps.map { binaryRoot(it) }
                parser.outputClassesTo = binaryRoot(module)
                parser.artifactId = "$artifactId/$module"
                parser.printBytecode = true
                parser.compile()
            }
        } else {
            // pre-jigsaw, compile everything at once
            compile(artifactId,
                    tmp,
                    dependencies = emptyList(),
                    includeRunningVmBootclasspath = false,
                    printer = printer)
        }
    }
}