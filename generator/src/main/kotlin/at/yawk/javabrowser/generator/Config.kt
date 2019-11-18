package at.yawk.javabrowser.generator

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.DbConfig
import java.nio.file.Paths

/**
 * @author yawkat
 */
data class Config(
        val database: DbConfig,
        val mavenResolver: MavenDependencyResolver.Config,
        val artifacts: List<ArtifactConfig> = listOf(
                ArtifactConfig.OldJava("8", Paths.get("/usr/lib/jvm/java-8-openjdk/src.zip"), ArtifactMetadata()),
                ArtifactConfig.Java("10", Paths.get("/usr/lib/jvm/java-10-openjdk"), ArtifactMetadata()),
                ArtifactConfig.Maven("com.google.guava", "guava", "25.1-jre")
        )
)