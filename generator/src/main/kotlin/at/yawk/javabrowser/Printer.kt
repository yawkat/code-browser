package at.yawk.javabrowser

/**
 * @author yawkat
 */
class Printer {
    val sourceFiles: MutableMap<String, AnnotatedSourceFile> = HashMap()
    val bindings: MutableMap<String, String> = HashMap()
    val types: MutableSet<String> = HashSet()

    fun registerBinding(binding: String, sourceFilePath: String) {
        bindings[binding] = sourceFilePath
    }

    fun registerType(type: String) {
        types.add(type)
    }

    fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
        sourceFiles[path] = sourceFile
    }
}