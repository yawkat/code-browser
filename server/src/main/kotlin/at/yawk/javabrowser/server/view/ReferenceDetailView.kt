package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.VersionComparator
import com.google.common.collect.Table
import java.net.URI

/**
 * @author yawkat
 */
data class ReferenceDetailView(
        val targetBinding: String,
        val baseUri: URI,
        val type: BindingRefType?,
        val sourceArtifactId: String?,

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
    val artifacts: List<String> = countsByArtifact.keys.sortedWith(VersionComparator)

    class TypeListing(
            val type: BindingRefType,
            /** null elements are ignored */
            val artifacts: Iterator<ArtifactListing?>
    )

    class ArtifactListing(
            val artifactId: String,
            /** null elements are ignored */
            val sourceFiles: Iterator<SourceFileListing?>
    )

    class SourceFileListing(
            val sourceFile: String,
            val items: List<Row>
    )

    data class Row(
            val type: BindingRefType?,
            val sourceArtifactId: String,
            val sourceFile: String,
            val sourceFileLine: Int,
            val sourceFileId: Int
    ) {
        val sourceLocation: URI
            get() = BindingResolver.location(sourceArtifactId, sourceFile, "#ref-$sourceFileId")
    }
}