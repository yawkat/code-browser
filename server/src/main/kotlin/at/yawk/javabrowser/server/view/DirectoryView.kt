package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.ParsedPath

data class DirectoryView(
    val realm: Realm,
    val newPath: ParsedPath.SourceFile,
    val oldPath: ParsedPath.SourceFile?,
    val entries: List<DirectoryEntry>,
    val alternatives: List<Alternative>
): View("directoryView.ftl") {
    data class DirectoryEntry(
        val name: String,
        /**
         * Source file path to this binding, including potential diff and realm query parameter.
         */
        val fullSourceFilePath: String,
        val isDirectory: Boolean,
        val diffResult: DeclarationNode.DiffResult? = null
    )
}