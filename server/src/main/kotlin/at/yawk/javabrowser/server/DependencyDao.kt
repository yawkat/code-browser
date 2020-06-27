package at.yawk.javabrowser.server

import org.skife.jdbi.v2.sqlobject.Bind
import org.skife.jdbi.v2.sqlobject.SqlQuery

/**
 * @author yawkat
 */
interface DependencyDao {
    @SqlQuery("select to_artifact from dependency where from_artifact = :artifactId")
    fun getDependencies(@Bind("artifactId") artifactId: Long): List<String>
}