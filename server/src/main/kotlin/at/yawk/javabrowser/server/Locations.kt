package at.yawk.javabrowser.server

import java.net.URI
import java.net.URLEncoder

object Locations {
    fun bindingHash(binding: String) = "#${URLEncoder.encode(binding, "UTF-8")}"

    fun location(artifactId: String, sourceFilePath: String, hash: String) =
        URI.create(fullSourceFilePath(artifactId, sourceFilePath) + hash)!!

    fun fullSourceFilePath(artifactId: String, sourceFilePath: String) =
        "/$artifactId/$sourceFilePath"

    fun toPath(parsedPath: ParsedPath) = when (parsedPath) {
        is ParsedPath.SourceFile -> fullSourceFilePath(parsedPath.artifact.stringId, parsedPath.sourceFilePath)
        is ParsedPath.Group, is ParsedPath.LeafArtifact -> "/${parsedPath.artifact.stringId}"
    }

    /**
     * Path to the diff view of two source files. Source file paths *must not* contain binding hashes.
     */
    fun diffPath(sourceFileNew: String, sourceFileOld: String) =
        "$sourceFileNew?diff=${URLEncoder.encode(sourceFileOld, "UTF-8")}"
}