package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.ParsedPath

/**
 * @author yawkat
 */
@Suppress("unused")
data class LeafArtifactView(
        val path: ParsedPath.LeafArtifact,
        val oldPath: ParsedPath.LeafArtifact?,
        val artifactMetadata: ArtifactMetadata,
        val dependencies: List<Dependency>,
        val topLevelPackages: Iterator<DeclarationNode>,
        val alternatives: List<Alternative>,
        /** keys should be [Realm.name], using [String] keys because that's what freemarker templates can access */
        val topDirectoryEntries: Map<String, List<DirectoryView.DirectoryEntry>>
) : View("leafArtifact.ftl") {
    data class Dependency(
            val prefix: String?,
            val suffix: String,
            val aliasedTo: String?
    )
}
