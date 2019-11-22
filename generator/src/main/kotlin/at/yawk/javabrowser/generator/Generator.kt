package at.yawk.javabrowser.generator

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.DbMigration
import org.eclipse.collections.impl.factory.Bags
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger("at.yawk.javabrowser.generator.Generator")

fun main(args: Array<String>) {
    val config = Config.fromFile(Paths.get(args[0]))

    val duplicateArtifactBag = Bags.mutable.withAll(config.artifacts)
    if (duplicateArtifactBag.toMapOfItemToCount().values.any { it > 1 }) {
        log.error("Duplicate artifacts: $duplicateArtifactBag")
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

    val compiler = Compiler(dbi, session, MavenDependencyResolver(config.mavenResolver))
    val artifactIds = ArrayList<String>()
    for (artifact in config.artifacts) {
        val id = Compiler.getArtifactId(artifact)

        artifactIds.add(id)
        try {
            when (artifact) {
                is ArtifactConfig.OldJava -> compiler.compileOldJava(id, artifact)
                is ArtifactConfig.Java -> compiler.compileJava(id, artifact)
                is ArtifactConfig.Android -> compiler.compileAndroid(id, artifact)
                is ArtifactConfig.Maven -> compiler.compileMaven(id, artifact)
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to compile artifact $artifact", e)
        }
    }

    session.execute()
}