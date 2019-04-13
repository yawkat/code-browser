package at.yawk.javabrowser.generator

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.DbMigration
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.slf4j.LoggerFactory
import java.io.File

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger("at.yawk.javabrowser.generator.Generator")

fun main(args: Array<String>) {

    val config = ObjectMapper(YAMLFactory()).findAndRegisterModules().readValue(File(args[0]), Config::class.java)
    val dbi = config.database.start(mode = DbConfig.Mode.GENERATOR)

    dbi.inTransaction { conn, _ ->
        // if the old schema is missing entirely, create it now so that version checks, etc. below can rely on the
        // tables existing (albeit empty)
        conn.update("create schema if not exists data")
        DbMigration.initDataSchema(conn)
        DbMigration.createIndices(conn)
    }

    val session = Session(dbi)

    val compiler = Compiler(dbi, session)
    val artifactIds = ArrayList<String>()
    for (artifact in config.artifacts) {
        val id = when (artifact) {
            is ArtifactConfig.OldJava -> "java/${artifact.version}"
            is ArtifactConfig.Java -> "java/${artifact.version}"
            is ArtifactConfig.Maven -> "${artifact.groupId}/${artifact.artifactId}/${artifact.version}"
        }

        artifactIds.add(id)
        try {
            when (artifact) {
                is ArtifactConfig.OldJava -> compiler.compileOldJava(id, artifact)
                is ArtifactConfig.Java -> compiler.compileJava(id, artifact)
                is ArtifactConfig.Maven -> compiler.compileMaven(id, artifact)
            }
        } catch (e: Exception) {
            log.error("Failed to compile artifact {}", artifact, e)
        }
    }

    session.execute()
}