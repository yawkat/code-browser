package at.yawk.javabrowser.server

import org.jdbi.v3.sqlobject.customizer.Bind
import org.jdbi.v3.sqlobject.statement.SqlQuery

/**
 * @author yawkat
 */
interface DependencyDao {
    @SqlQuery("select to_artifact from dependency where from_artifact = :artifactId")
    fun getDependencies(@Bind("artifactId") artifactId: Long): List<String>
}