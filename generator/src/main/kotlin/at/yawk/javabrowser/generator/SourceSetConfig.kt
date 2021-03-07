package at.yawk.javabrowser.generator

import java.nio.file.Path

data class SourceSetConfig(
        val debugTag: String,
        val sourceRoot: Path,
        val dependencies: List<Path>,
        val includeRunningVmBootclasspath: Boolean = true,
        val pathPrefix: String = "",
        val outputClassesTo: Path? = null,

        val quirkIsJavaBase: Boolean = false
)
