package at.yawk.javabrowser.generator

import org.jboss.shrinkwrap.resolver.api.NoResolvedResultException
import org.jboss.shrinkwrap.resolver.api.maven.Maven
import java.nio.file.Paths

fun main(args: Array<String>) {
    val cfg = Config.fromFile(Paths.get(args[0]))
    val mavenArtifacts = cfg.artifacts
        .filterIsInstance<ArtifactConfig.Maven>()
        .groupBy { "${it.groupId}:${it.artifactId}" }
        .mapValues { (_, v) -> v.mapTo(HashSet()) { it.version } }
    for ((qualifier, versions) in mavenArtifacts) {
        if (qualifier.startsWith("commons-")) continue
        val resolved = try {
            Maven.configureResolver()
                .withMavenCentralRepo(true)
                .resolve("$qualifier:LATEST").withoutTransitivity().asSingleResolvedArtifact()
        } catch (e: NoResolvedResultException) {
            println(e)
            continue
        }
        val latestVersion = resolved.resolvedVersion
        if (latestVersion !in versions) {
            println("$qualifier $latestVersion <- $versions")
        }
    }
}