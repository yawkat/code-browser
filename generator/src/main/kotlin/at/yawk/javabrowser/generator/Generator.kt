package at.yawk.javabrowser.generator

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.generator.artifact.COMPILER_VERSION
import at.yawk.javabrowser.generator.artifact.compileAndroid
import at.yawk.javabrowser.generator.artifact.compileJdk
import at.yawk.javabrowser.generator.artifact.compileMaven
import at.yawk.javabrowser.generator.artifact.getArtifactId
import at.yawk.javabrowser.generator.artifact.resolveMavenMetadata
import com.google.common.collect.HashMultiset
import org.skife.jdbi.v2.Handle
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

    val newDb = dbi.inTransaction { conn: Handle, _ ->
        !conn.select("select 1 from information_schema.tables where table_schema = 'data' and table_name = 'artifacts'").any()
    }

    for (artifact in config.artifacts) {
        val id = getArtifactId(artifact)
        try {
            if (!newDb && dbi.inTransaction { conn: Handle, _ ->
                        conn.select("select 1 from artifacts where id = ? and lastCompileVersion >= ?", id, COMPILER_VERSION).any()
                    }) {
                // already compiled with this version.
                continue
            }

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
        } catch (e: Exception) {
            throw RuntimeException("Failed to compile artifact $artifact", e)
        }
    }

    session.execute()
}