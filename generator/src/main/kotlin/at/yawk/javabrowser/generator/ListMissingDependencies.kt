package at.yawk.javabrowser.generator

import java.nio.file.Paths
import java.util.TreeSet

/**
 * @author yawkat
 */
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
            if (mavenArtifacts.none { it.groupId == dependency.coordinate.groupId && it.artifactId == dependency.coordinate.artifactId }) {
                val s = """maven("${dependency.coordinate.groupId}", "${dependency.coordinate.artifactId}", "${dependency.coordinate.version}")"""
                missing.add(s)
            }
        }
    }
    for (s in missing) {
        println(s)
    }
}