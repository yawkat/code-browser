package at.yawk.javabrowser.generator

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.DbMigration
import at.yawk.javabrowser.PositionedAnnotation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates
import org.postgresql.util.PSQLException
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.testng.Assert
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import ru.yandex.qatools.embed.postgresql.EmbeddedPostgres
import java.net.URL
import java.sql.DriverManager

/**
 * @author yawkat
 */
class CompilerTest {
    private lateinit var dbi: DBI
    private lateinit var embeddedPostgres: EmbeddedPostgres

    @BeforeMethod(timeOut = 1000 * 60)
    fun initDb() {
        embeddedPostgres = EmbeddedPostgres()
        val url = embeddedPostgres.start()
        val config = DbConfig(url, EmbeddedPostgres.DEFAULT_USER, EmbeddedPostgres.DEFAULT_PASSWORD)
        // wait for db to come up
        while (true) {
            try {
                DriverManager.getConnection(config.url, config.user, config.password).close()
            } catch (e: PSQLException) {
                if (e.message.orEmpty().contains("the database system is starting up")) {
                    Thread.sleep(500)
                    continue
                }
                throw e
            }
            break
        }
        dbi = config.start(mode = DbConfig.Mode.GENERATOR)
        dbi.inTransaction { conn, _ ->
            conn.update("create schema data")
            DbMigration.initDataSchema(conn)
            DbMigration.createIndices(conn)
        }
    }

    @AfterMethod(alwaysRun = true)
    fun shutdownDb() {
        embeddedPostgres.stop()
    }

    @Test
    fun maven() {
        val session = Session(dbi)
        val compiler = Compiler(dbi, session)
        compiler.compileMaven("com.google.guava/guava/25.1-jre",
                ArtifactConfig.Maven("com.google.guava", "guava", "25.1-jre"))
        session.execute()

        dbi.inTransaction { conn: Handle, _ ->
            Assert.assertEquals(
                    conn.select("select id, lastcompileversion from artifacts"),
                    listOf(mapOf(
                            "id" to "com.google.guava/guava/25.1-jre",
                            "lastcompileversion" to Compiler.VERSION
                    ))
            )

            Assert.assertTrue(
                    (conn.select("select count(*) from sourceFiles").single().values.single() as Number).toInt() > 100
            )

            println((conn.select("select * from sourceFiles limit 1").single()["json"] as ByteArray)
                    .toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `maven metadata`() {
        val compiler = Compiler(dbi, Session(dbi))

        Assert.assertEquals(
                compiler.resolveMavenMetadata(
                        ArtifactConfig.Maven("com.google.guava", "guava", "25.1-jre")),
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
                compiler.resolveMavenMetadata(
                        ArtifactConfig.Maven("io.undertow", "undertow-core", "2.0.9.Final")),
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

        compiler.resolveMavenMetadata(
                ArtifactConfig.Maven("org.openjfx", "javafx-controls", "11"))
    }

    @Test
    fun `super method ref`() {
        val session = Session(dbi)
        val compiler = Compiler(dbi, session)
        compiler.compileMaven("x",
                ArtifactConfig.Maven("io.ebean", "ebean", "11.36.4"))
        session.execute()

        val count = dbi.withHandle {
            it.select("select count from binding_references_count_view where targetbinding = 'io.ebean.ExtendedServer#findList(io.ebean.SqlQuery,io.ebean.Transaction)' and type = ?",
                    BindingRefType.SUPER_METHOD.id)[0]["count"] as Number
        }.toInt()
        Assert.assertEquals(count, 1)
    }

    @Test
    fun `spring-webmvc declarations`() {
        val session = Session(dbi)
        val compiler = Compiler(dbi, session, MavenDependencyResolver(MavenDependencyResolver.Config(
                excludeDependencies = listOf(MavenCoordinates.createCoordinate("com.ibm.websphere:uow:jar:6.0.2.17"),
                        MavenCoordinates.createCoordinate("xerces:xerces-impl:jar:2.6.2")))))
        compiler.compileMaven("x",
                ArtifactConfig.Maven("org.springframework", "spring-webmvc", "5.1.5.RELEASE"))
        session.execute()

        val count = dbi.withHandle {
            it.select("select count(*) from bindings where binding like 'org.springframework.web.servlet.i18n.SessionLocaleResolver#resolveLocaleContext(%)'")[0]["count"] as Number
        }.toInt()
        Assert.assertEquals(count, 1)
    }

    @Test(enabled = false) // takes ~4min to complete
    fun `android anon class`() {
        val session = Session(dbi)
        val compiler = Compiler(dbi, session)
        compiler.compileAndroid("x",
                ArtifactConfig.Android(
                        repos = listOf(
                                ArtifactConfig.GitRepo(URL("https://android.googlesource.com/platform/frameworks/base"), "android-9.0.0_r35"),
                                ArtifactConfig.GitRepo(URL("https://android.googlesource.com/platform/system/vold"), "android-9.0.0_r35")
                        ),
                        version = "android-9.0.0_r35",
                        metadata = ArtifactMetadata()
                ))
        session.execute()

        val bytes = dbi.withHandle {
            it.select("select annotations from sourceFiles where path = 'android/bluetooth/BluetoothDevice.java'")[0]["annotations"] as ByteArray
        }
        val decls = ObjectMapper().findAndRegisterModules()
                .readValue<List<PositionedAnnotation>>(bytes)
                .map { it.annotation }
                .filterIsInstance<BindingDecl>()
        val single = decls.single { it.binding == "android.bluetooth.BluetoothDevice\$1" }
        Assert.assertNotNull(single.parent)
    }
}