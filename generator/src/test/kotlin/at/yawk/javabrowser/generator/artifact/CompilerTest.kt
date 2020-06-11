package at.yawk.javabrowser.generator.artifact

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.GeneratorSourceFile
import at.yawk.javabrowser.generator.MavenDependencyResolver
import at.yawk.javabrowser.generator.Printer
import at.yawk.javabrowser.generator.PrinterWithDependencies
import kotlinx.coroutines.runBlocking
import org.testng.Assert
import org.testng.annotations.Test
import java.net.URL
import java.util.concurrent.atomic.LongAdder

/**
 * @author yawkat
 */
class CompilerTest {
    @Test
    fun maven() {
        val sourceFileCount = LongAdder()
        runBlocking {
            compileMaven(
                    MavenDependencyResolver(),
                    object : PrinterWithDependencies {
                        override fun addDependency(dependency: String) {
                        }

                        override fun addAlias(alias: String) {
                            Assert.fail()
                        }

                        override fun addSourceFile(path: String,
                                                   sourceFile: GeneratorSourceFile,
                                                   tokens: List<Tokenizer.Token>,
                                                   realm: Realm) {
                            sourceFileCount.increment()
                        }
                    },
                    "com.google.guava/guava/25.1-jre",
                    ArtifactConfig.Maven("com.google.guava",
                            "guava",
                            "25.1-jre")
            )
        }
        Assert.assertTrue(sourceFileCount.sum() > 100)
    }

    @Test
    fun `spring-instrument`() {
        val sourceFileCount = LongAdder()
        runBlocking {
            compileMaven(
                    MavenDependencyResolver(),
                    object : PrinterWithDependencies {
                        override fun addDependency(dependency: String) {
                        }

                        override fun addAlias(alias: String) {
                            Assert.fail()
                        }

                        override fun addSourceFile(path: String,
                                                   sourceFile: GeneratorSourceFile,
                                                   tokens: List<Tokenizer.Token>,
                                                   realm: Realm) {
                            sourceFileCount.increment()
                        }
                    },
                    "com.google.guava/guava/25.1-jre",
                    ArtifactConfig.Maven("org.springframework",
                            "spring-instrument",
                            "5.1.5.RELEASE")
            )
        }
        Assert.assertEquals(sourceFileCount.sum(), 2)
    }

    @Test
    fun `maven metadata`() {
        Assert.assertEquals(
                resolveMavenMetadata(
                        ArtifactConfig.Maven("com.google.guava",
                                "guava",
                                "25.1-jre")),
                ArtifactMetadata(
                        licenses = listOf(ArtifactMetadata.License(name = "The Apache Software License, Version 2.0",
                                url = "http://www.apache.org/licenses/LICENSE-2.0.txt")),
                        url = "https://github.com/google/guava/guava",
                        description = "Guava is a suite of core and expanded libraries that include\n" +
                                "    utility classes, google's collections, io classes, and much\n" +
                                "    much more.",
                        issueTracker = ArtifactMetadata.IssueTracker(type = "GitHub Issues",
                                url = "https://github.com/google/guava/issues"),
                        developers = listOf(
                                ArtifactMetadata.Developer(name = "Kevin Bourrillion",
                                        email = "kevinb@google.com",
                                        organization = at.yawk.javabrowser.ArtifactMetadata.Organization(
                                                name = "Google", url = "http://www.google.com"))
                        ))
        )
        // this one has a parent pom
        Assert.assertEquals(
                resolveMavenMetadata(
                        ArtifactConfig.Maven("io.undertow",
                                "undertow-core",
                                "2.0.9.Final")),
                ArtifactMetadata(
                        licenses = listOf(ArtifactMetadata.License(name = "Apache License Version 2.0",
                                url = "http://repository.jboss.org/licenses/apache-2.0.txt")),
                        url = "http://www.jboss.org/undertow-parent/undertow-core",
                        description = "Undertow",
                        issueTracker = ArtifactMetadata.IssueTracker(type = "JIRA",
                                url = "https://issues.jboss.org/"),
                        organization = ArtifactMetadata.Organization(name = "JBoss by Red Hat",
                                url = "http://www.jboss.org"),
                        developers = listOf(
                                ArtifactMetadata.Developer(name = "JBoss.org Community",
                                        organization = at.yawk.javabrowser.ArtifactMetadata.Organization(
                                                name = "JBoss.org", url = "http://www.jboss.org"))
                        ))
        )

        resolveMavenMetadata(
                ArtifactConfig.Maven("org.openjfx",
                        "javafx-controls",
                        "11"))
    }

    @Test(enabled = false) // takes ~4min to complete
    fun `android anon class`() {
        var foundFile = false
        runBlocking {
            compileAndroid(
                    object : PrinterWithDependencies {
                        override fun addDependency(dependency: String) {
                        }

                        override fun addAlias(alias: String) {
                        }

                        override fun addSourceFile(path: String,
                                                   sourceFile: GeneratorSourceFile,
                                                   tokens: List<Tokenizer.Token>,
                                                   realm: Realm) {
                            if (path == "android/bluetooth/BluetoothDevice.java") {
                                val single = sourceFile.entries
                                        .map { it.annotation }
                                        .filterIsInstance<BindingDecl>()
                                        .single { it.binding == "android.bluetooth.BluetoothDevice\$1" }
                                Assert.assertNotNull(single.parent)

                                foundFile = true
                            }
                        }
                    },
                    "x",
                    ArtifactConfig.Android(
                            repos = listOf(
                                    ArtifactConfig.GitRepo(URL("https://android.googlesource.com/platform/frameworks/base"),
                                            "android-9.0.0_r35"),
                                    ArtifactConfig.GitRepo(URL("https://android.googlesource.com/platform/system/vold"),
                                            "android-9.0.0_r35")
                            ),
                            buildTools = URL("https://dl-ssl.google.com/android/repository/build-tools_r28.0.3-linux.zip"),
                            version = "android-9.0.0_r35",
                            metadata = ArtifactMetadata()
                    ))
        }

        Assert.assertTrue(foundFile)
    }

    @Test(enabled = false)
    fun `java 9`() {
        runBlocking {
            compileJdk(
                    object : Printer {
                        override fun addSourceFile(path: String,
                                                   sourceFile: GeneratorSourceFile,
                                                   tokens: List<Tokenizer.Token>,
                                                   realm: Realm) {
                        }
                    },
                    "x",
                    ArtifactConfig.Java(
                            version = "9",
                            archiveUrl = URL("https://ci.yawk.at/job/jdk-hg-snapshot/repo_path=jdk-updates_jdk9u/lastSuccessfulBuild/artifact/jdk-updates_jdk9u.tar.zst"),
                            jigsaw = true,
                            metadata = ArtifactMetadata()
                    ))
        }
    }

    @Test(enabled = false)
    fun `java 10`() {
        var any = false
        runBlocking {
            compileJdk(
                    object : Printer {
                        override fun addSourceFile(path: String,
                                                   sourceFile: GeneratorSourceFile,
                                                   tokens: List<Tokenizer.Token>,
                                                   realm: Realm) {
                            any = true
                        }
                    },
                    "x",
                    ArtifactConfig.Java(
                            version = "10",
                            archiveUrl = URL("https://ci.yawk.at/job/jdk-hg-snapshot/repo_path=jdk-updates_jdk10u/lastSuccessfulBuild/artifact/jdk-updates_jdk10u.tar.zst"),
                            jigsaw = true,
                            metadata = ArtifactMetadata()
                    ))
        }
        Assert.assertTrue(any)
    }

    @Test(enabled = false)
    fun android() {
        var any = false
        runBlocking {
            compileAndroid(
                    object : PrinterWithDependencies {
                        override fun addDependency(dependency: String) {
                        }

                        override fun addAlias(alias: String) {
                            Assert.fail()
                        }

                        override fun addSourceFile(path: String,
                                                   sourceFile: GeneratorSourceFile,
                                                   tokens: List<Tokenizer.Token>,
                                                   realm: Realm) {
                            any = true
                        }
                    },
                    "x",
                    ArtifactConfig.Android(
                            version = "x",
                            repos = listOf(
                                    ArtifactConfig.GitRepo(
                                            url = URL("https://android.googlesource.com/platform/frameworks/base"),
                                            tag = "android-9.0.0_r35"
                                    ),
                                    ArtifactConfig.GitRepo(
                                            url = URL("https://android.googlesource.com/platform/system/vold"),
                                            tag = "android-9.0.0_r35"
                                    )
                            ),
                            buildTools = URL("https://dl-ssl.google.com/android/repository/build-tools_r28.0.3-linux.zip"),
                            metadata = ArtifactMetadata()
                    ))
        }
        Assert.assertTrue(any)
    }
}