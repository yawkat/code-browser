package at.yawk.javabrowser.server

import io.dropwizard.Configuration
import io.dropwizard.db.DataSourceFactory
import java.nio.file.Paths

/**
 * @author yawkat
 */
class Config : Configuration() {
    lateinit var database: DataSourceFactory
    var compilerThreads = Runtime.getRuntime().availableProcessors()
    var artifacts: List<Artifact> = listOf(
            Artifact.OldJava("8", Paths.get("/usr/lib/jvm/java-8-openjdk/src.zip")),
            Artifact.Java("10", Paths.get("/usr/lib/jvm/java-10-openjdk")),
            Artifact.Maven("com.google.guava", "guava", listOf("25.1-jre"))
    )
}