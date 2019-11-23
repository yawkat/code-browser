package at.yawk.javabrowser.generator

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.DbConfig
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

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
) {
    companion object {
        object Configuration : ScriptCompilationConfiguration({
            defaultImports(
                    "at.yawk.javabrowser.generator.*",
                    "at.yawk.javabrowser.*"
            )
        })

        @KotlinScript(
                fileExtension = "generator.kts",
                compilationConfiguration = Configuration::class
        )
        abstract class ConfigScript {
            var dbConfig: DbConfig? = null
            var mavenResolver: MavenDependencyResolver.Config = MavenDependencyResolver.Config()
            val artifacts = ArrayList<ArtifactConfig>()

            fun database(
                    url: String,
                    user: String,
                    password: String
            ) {
                if (dbConfig != null) throw IllegalStateException()
                dbConfig = DbConfig(url, user, password)
            }

            fun mavenResolver(config: MavenDependencyResolver.Config) {
                this.mavenResolver = config
            }

            fun artifacts(f: ArtifactCollector.() -> Unit) {
                ArtifactCollector().f()
            }

            inner class ArtifactCollector {
                fun oldJava(version: String, src: String, metadata: ArtifactMetadata) {
                    artifacts.add(ArtifactConfig.OldJava(version, Paths.get(src), metadata))
                }

                fun java(version: String, baseDir: String, metadata: ArtifactMetadata) {
                    artifacts.add(ArtifactConfig.Java(version, Paths.get(baseDir), metadata))
                }

                fun android(version: String, repos: List<ArtifactConfig.GitRepo>, metadata: ArtifactMetadata) {
                    artifacts.add(ArtifactConfig.Android(repos, version, metadata))
                }

                fun maven(groupId: String, artifactId: String, version: String, metadata: ArtifactMetadata? = null,
                          f: MavenBuilder.() -> Unit = {}) {
                    val builder = MavenBuilder(groupId, artifactId, version, metadata)
                    builder.f()
                    artifacts.add(builder.build())
                }
            }

            class MavenBuilder(private val groupId: String,
                               private val artifactId: String,
                               val version: String,
                               private val metadata: ArtifactMetadata? = null) {
                private val aliases = ArrayList<ArtifactConfig.Maven>()

                fun alias(groupId: String, artifactId: String, version: String = this.version) {
                    aliases.add(ArtifactConfig.Maven(groupId, artifactId, version))
                }

                fun build() = ArtifactConfig.Maven(groupId, artifactId, version, metadata, aliases)
            }
        }

        fun fromFile(path: Path): Config {
            val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<ConfigScript> {
                jvm {
                    dependenciesFromClassloader(wholeClasspath = true)
                }
            }

            val result = BasicJvmScriptingHost().eval(
                    path.toFile().toScriptSource(),
                    compilationConfiguration,
                    null
            )

            val configScript = result.valueOrThrow().returnValue.scriptInstance as ConfigScript
            return Config(
                    database = configScript.dbConfig!!,
                    artifacts = configScript.artifacts,
                    mavenResolver = configScript.mavenResolver
            )
        }
    }
}