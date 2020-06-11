package at.yawk.javabrowser.generator.artifact

import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.Printer
import at.yawk.javabrowser.generator.SourceFileParser
import com.google.common.io.MoreFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

const val COMPILER_VERSION = 34

fun getArtifactId(artifact: ArtifactConfig) = when (artifact) {
    is ArtifactConfig.Java -> "java/${artifact.version}"
    is ArtifactConfig.Android -> "android/${artifact.version}"
    is ArtifactConfig.Maven -> "${artifact.groupId}/${artifact.artifactId}/${artifact.version}"
}

internal suspend fun compile(
        artifactId: String,
        sourceRoot: Path,
        dependencies: List<Path>,
        includeRunningVmBootclasspath: Boolean,
        printer: Printer,
        pathPrefix: String = ""
) {
    val parser = SourceFileParser(sourceRoot, printer)
    parser.includeRunningVmBootclasspath = includeRunningVmBootclasspath
    parser.pathPrefix = pathPrefix
    parser.dependencies = dependencies
    parser.artifactId = artifactId
    parser.printBytecode = true
    parser.compile()
}

inline fun tempDir(f: (Path) -> Unit) {
    val tmp = Files.createTempDirectory(Paths.get("/var/tmp"), "compile")
    var delete = !java.lang.Boolean.getBoolean("at.yawk.javabrowser.generator.keepTempOnError")
    try {
        f(tmp)
        delete = true
    } finally {
        if (delete) {
            MoreFiles.deleteRecursively(tmp)
        }
    }
}