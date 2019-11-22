package at.yawk.javabrowser.generator

import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate
import java.nio.file.Paths
import java.util.TreeSet

/**
 * @author yawkat
 */
private fun matches(coordinate: MavenCoordinate, it: ArtifactConfig.Maven): Boolean =
        (it.groupId == coordinate.groupId && it.artifactId == coordinate.artifactId) ||
                it.aliases.any { alias -> matches(coordinate, alias) }

fun main(args: Array<String>) {
    val cfg = Config.fromFile(Paths.get(args[0]))
    val dependencyResolver = MavenDependencyResolver(cfg.mavenResolver)
    val mavenArtifacts = cfg.artifacts.filterIsInstance<ArtifactConfig.Maven>()
    val missing = TreeSet<String>()
    for (artifact in mavenArtifacts) {
        val dependencies = dependencyResolver.getMavenDependencies(
                artifact.groupId,
                artifact.artifactId,
                artifact.version)

        for (dependency in dependencies) {
            if (mavenArtifacts.none { matches(dependency.coordinate, it) }) {
                val s = """maven("${dependency.coordinate.groupId}", "${dependency.coordinate.artifactId}", "${dependency.coordinate.version}")"""
                missing.add(s)
            }
        }
    }
    for (s in missing) {
        println(s)
    }
}