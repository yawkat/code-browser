package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.Locations
import at.yawk.javabrowser.server.ParsedPath
import at.yawk.javabrowser.server.VersionComparator
import at.yawk.javabrowser.server.artifact.ArtifactNode

/**
 * Alternate version for a source file, artifact or directory.
 */
data class Alternative(
    /**
     * The alternative artifact.
     */
    val artifact: ArtifactNode,
    /**
     * The absolute path to the alternative, excluding any hash.
     */
    val path: String,
    /**
     * The complete absolute path to the diff view.
     */
    val diffPath: String?
) {
    companion object {
        internal fun fromPathPair(
            realm: Realm?,
            baseline: ParsedPath,
            alt: ParsedPath
        ): Alternative {
            val cmp = VersionComparator.compare(baseline.artifact.stringId, alt.artifact.stringId)
            val realmSuffix =
                if (realm != null &&
                    ((baseline is ParsedPath.SourceFile && baseline.realmFromExtension != realm) ||
                            (alt is ParsedPath.SourceFile && alt.realmFromExtension != realm))
                )
                    "&realm=$realm"
                else ""
            return Alternative(
                alt.artifact, Locations.toPath(alt), when {
                    cmp > 0 -> Locations.diffPath(
                        sourceFileNew = Locations.toPath(alt),
                        sourceFileOld = Locations.toPath(baseline)
                    ) + realmSuffix
                    cmp < 0 -> Locations.diffPath(
                        sourceFileNew = Locations.toPath(baseline),
                        sourceFileOld = Locations.toPath(alt)
                    ) + realmSuffix
                    else -> null
                }
            )
        }
    }
}