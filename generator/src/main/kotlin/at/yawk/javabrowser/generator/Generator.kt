package at.yawk.javabrowser.generator

import at.yawk.javabrowser.server.ArtifactConfig
import at.yawk.javabrowser.server.Compiler
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger("at.yawk.javabrowser.generator.Generator")

fun main(args: Array<String>) {

    val config = ObjectMapper(YAMLFactory()).findAndRegisterModules().readValue(File(args[0]), Config::class.java)
    val dbi = config.database.start()
    val objectMapper = ObjectMapper()

    val compiler = Compiler(dbi, objectMapper)
    val compileExecutor = Executors.newFixedThreadPool(config.compilerThreads)
    val artifactIds = ArrayList<String>()
    for (artifact in config.artifacts) {
        val id = when (artifact) {
            is ArtifactConfig.OldJava -> "java/${artifact.version}"
            is ArtifactConfig.Java -> "java/${artifact.version}"
            is ArtifactConfig.Maven -> "${artifact.groupId}/${artifact.artifactId}/${artifact.version}"
        }

        artifactIds.add(id)
        compileExecutor.execute {
            try {
                when (artifact) {
                    is ArtifactConfig.OldJava -> compiler.compileOldJava(id, artifact)
                    is ArtifactConfig.Java -> compiler.compileJava(id, artifact)
                    is ArtifactConfig.Maven -> compiler.compileMaven(id, artifact)
                }
                log.info("$id is ready")
            } catch (e: Exception) {
                log.error("Failed to compile artifact {}", artifact, e)
            }
        }
    }
    compileExecutor.shutdown()
}