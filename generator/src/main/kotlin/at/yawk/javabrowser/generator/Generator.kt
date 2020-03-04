package at.yawk.javabrowser.generator

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.DbMigration
import at.yawk.javabrowser.generator.artifact.compileAndroid
import at.yawk.javabrowser.generator.artifact.compileJdk
import at.yawk.javabrowser.generator.artifact.compileMaven
import at.yawk.javabrowser.generator.artifact.getArtifactId
import at.yawk.javabrowser.generator.artifact.needsRecompile
import at.yawk.javabrowser.generator.artifact.resolveMavenMetadata
import com.google.common.collect.HashMultiset
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger("at.yawk.javabrowser.generator.Generator")

fun main(args: Array<String>) {
    val config = Config.fromFile(Paths.get(args[0]))

    val duplicateArtifactBag = HashMultiset.create(config.artifacts)
    if (duplicateArtifactBag.entrySet().any { it.count > 1 }) {
        log.error("Duplicate artifacts: ${duplicateArtifactBag.entrySet().filter { it.count > 1 }.map { it.element }}")
        return
    }

    val dbi = config.database.start(mode = DbConfig.Mode.GENERATOR)

    dbi.inTransaction { conn, _ ->
        // if the old schema is missing entirely, create it now so that version checks, etc. below can rely on the
        // tables existing (albeit empty)
        conn.update("create schema if not exists data")
        DbMigration.initDataSchema(conn)
        DbMigration.createIndices(conn)
    }

    val session = Session(dbi)
    val mavenDependencyResolver = MavenDependencyResolver(config.mavenResolver)

    val artifactIds = config.artifacts.map { getArtifactId(it) }.sorted()
    val duplicate = HashSet<String>()
    for (i in artifactIds.indices) {
        if (i > 0 && artifactIds[i] == artifactIds[i - 1]) {
            duplicate.add(artifactIds[i])
        }
    }
    if (duplicate.isNotEmpty()) {
        throw RuntimeException("Duplicate artifacts: $duplicate")
    }

    for (artifact in config.artifacts) {
        val id = getArtifactId(artifact)
        try {
            if (needsRecompile(dbi, id)) {
                val metadata = when (artifact) {
                    is ArtifactConfig.Java -> artifact.metadata
                    is ArtifactConfig.Maven -> resolveMavenMetadata(artifact)
                    is ArtifactConfig.Android -> artifact.metadata
                }
                session.withPrinter(id, metadata) { printer ->
                    when (artifact) {
                        is ArtifactConfig.Java -> compileJdk(printer, id, artifact)
                        is ArtifactConfig.Android -> compileAndroid(printer, id, artifact)
                        is ArtifactConfig.Maven -> compileMaven(mavenDependencyResolver, printer, id, artifact)
                    }
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to compile artifact $artifact", e)
        }
    }

    session.execute()
}