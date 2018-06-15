package at.yawk.javabrowser.server

import org.skife.jdbi.v2.sqlobject.Bind
import org.skife.jdbi.v2.sqlobject.SqlQuery

/**
 * @author yawkat
 */
interface DependencyDao {
    @SqlQuery("select toArtifactId from dependencies where fromArtifactId = :artifactId")
    fun getDependencies(@Bind("artifactId") artifactId: String): List<String>
}