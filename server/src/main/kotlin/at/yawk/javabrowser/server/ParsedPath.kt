package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.artifact.ArtifactNode

private fun realmForSourcePath(path: String): Realm? {
    if (path.endsWith(".java")) return Realm.SOURCE
    if (path.endsWith(".class")) return Realm.BYTECODE
    return null
}

sealed class ParsedPath(val artifact: ArtifactNode) {
    /**
     * Might also be a directory
     */
    class SourceFile(artifact: ArtifactNode, val sourceFilePath: String) : ParsedPath(artifact) {
        val realmFromExtension = realmForSourcePath(sourceFilePath)
    }

    class LeafArtifact(artifact: ArtifactNode) : ParsedPath(artifact)
    class Group(artifact: ArtifactNode) : ParsedPath(artifact)
}