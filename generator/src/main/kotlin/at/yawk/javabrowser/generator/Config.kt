package at.yawk.javabrowser.generator

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.server.ArtifactConfig
import java.nio.file.Paths

/**
 * @author yawkat
 */
data class Config(
        val database: DbConfig,
        val artifacts: List<ArtifactConfig> = listOf(
                ArtifactConfig.OldJava("8", Paths.get("/usr/lib/jvm/java-8-openjdk/src.zip")),
                ArtifactConfig.Java("10", Paths.get("/usr/lib/jvm/java-10-openjdk")),
                ArtifactConfig.Maven("com.google.guava", "guava", "25.1-jre")
        ),
        val compilerThreads: Int
)