package at.yawk.javabrowser.generator

import org.jboss.shrinkwrap.resolver.api.maven.Maven
import org.jboss.shrinkwrap.resolver.api.maven.MavenArtifactInfo
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType
import org.jboss.shrinkwrap.resolver.api.maven.ScopeType
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependencies

/**
 * @author yawkat
 */
fun getMavenDependencies(groupId: String,
                         artifactId: String,
                         version: String): List<MavenArtifactInfo> {
    val resolver = Maven.resolver()
    val s1 = resolver.addDependency(MavenDependencies.createDependency(
            MavenCoordinates.createCoordinate(
                    groupId, artifactId, version,
                    PackagingType.JAR, null),
            ScopeType.COMPILE,
            false
    )).resolve().withTransitivity()
    return s1.asResolvedArtifact().asList()
}