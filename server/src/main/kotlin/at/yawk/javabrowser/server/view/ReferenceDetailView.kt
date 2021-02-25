package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.Locations
import at.yawk.javabrowser.server.ParsedPath
import at.yawk.javabrowser.server.VersionComparator
import at.yawk.javabrowser.server.artifact.ArtifactNode
import com.google.common.collect.Table
import java.net.URI

/**
 * @author yawkat
 */
data class ReferenceDetailView(
        val realm: Realm,
        val targetBinding: String,
        val baseUri: URI,
        val type: BindingRefType?,
        val sourceArtifactId: ArtifactNode?,

        val countTable: Table<String, BindingRefType, Int>,
        val countsByType: Map<BindingRefType, Int>,
        val countsByArtifact: Map<String, Int>,
        val totalCount: Int,

        val resultLimit: Int?,
        val hitResultLimit: Boolean,
        val totalCountInSelection: Int,

        /** null elements are ignored */
        val results: Iterator<TypeListing?>
) : View("referenceDetail.ftl") {
    val artifactPath = sourceArtifactId?.let { ParsedPath.LeafArtifact(it) }

    val artifacts: List<String> = countsByArtifact.keys.sortedWith(VersionComparator)

    class TypeListing(
            val type: BindingRefType,
            /** null elements are ignored */
            val artifacts: Iterator<ArtifactListing?>
    ) {
        override fun toString() = "ArtifactListing(type=$type, artifacts=...)"
    }

    class ArtifactListing(
            val artifactId: String,
            /** null elements are ignored */
            val sourceFiles: Iterator<SourceFileListing?>
    ) {
        override fun toString() = "ArtifactListing(artifactId=$artifactId, sourceFiles=...)"
    }

    data class SourceFileListing(
            val sourceFile: String,
            val items: List<ReferenceListing>
    )

    class ReferenceListing(
            sourceArtifactStringId: String,
            sourceFile: String,
            val sourceFileLine: Int,
            sourceFileRefId: Int
    ) {
        val sourceLocation = Locations.location(sourceArtifactStringId, sourceFile, "#ref-$sourceFileRefId")

        override fun toString(): String {
            return "ReferenceListing(sourceFileLine=$sourceFileLine, sourceLocation=$sourceLocation)"
        }
    }
}