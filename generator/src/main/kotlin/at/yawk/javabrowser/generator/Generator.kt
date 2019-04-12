package at.yawk.javabrowser.generator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import java.io.File

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger("at.yawk.javabrowser.generator.Generator")

fun main(args: Array<String>) {

    val config = ObjectMapper(YAMLFactory()).findAndRegisterModules().readValue(File(args[0]), Config::class.java)
    val dbi = config.database.start()

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

    val totalArtifacts = dbi.inTransaction { conn: Handle, _ ->
        (conn.select("select count(*) as c from artifacts")[0]["c"] as Number).toInt()
    }
    val majorUpdate = session.taskCount > 0.6 * totalArtifacts
    log.info("Updating {} of {} artifacts, this seems to be a ${if (majorUpdate) "major" else "minor"} update",
            session.taskCount,
            totalArtifacts)

    session.execute(majorUpdate = majorUpdate)
}