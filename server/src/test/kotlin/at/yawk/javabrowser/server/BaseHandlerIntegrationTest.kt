package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.SourceFileView
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.SingletonSupport
import com.google.common.collect.ImmutableList
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.skife.jdbi.v2.DBI
import org.testng.Assert
import org.testng.annotations.BeforeClass
import org.testng.annotations.Optional
import org.testng.annotations.Parameters
import org.testng.annotations.Test

class BaseHandlerIntegrationTest {
    private lateinit var dbi: DBI
    private lateinit var artifactIndex: ArtifactIndex
    private lateinit var handler: BaseHandler

    @Parameters("dataSource")
    @BeforeClass
    fun setUp(@Optional dataSource: String?) {
        dbi = loadIntegrationTestDbi(dataSource)
        val artifactUpdater = ArtifactUpdater()
        artifactIndex = ArtifactIndex(artifactUpdater, dbi)
        val ftl = Ftl(ImageCache())
        val objectMapper = ObjectMapper().registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))
        handler = BaseHandler(dbi,
                ftl,
                BindingResolver(artifactUpdater, dbi, artifactIndex),
                artifactIndex,
                DeclarationTreeHandler(dbi, ftl, artifactIndex),
                SiteStatisticsService(dbi, artifactUpdater),
                AliasIndex(artifactUpdater, dbi),
                ArtifactMetadataCache(objectMapper, dbi, artifactUpdater))
    }

    @Test
    fun `source file`() {
        dbi.inTransaction { conn, _ ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val view = handler.sourceFile(
                    conn,
                    BaseHandler.ParsedPath.SourceFile(java8, "java/lang/String.java"),
                    diffWith = null
            )
            Assert.assertEquals(view.newInfo.realm, Realm.SOURCE)
            Assert.assertEquals(view.newInfo.artifactId, java8)
            Assert.assertEquals(view.newInfo.classpath, setOf("java/8"))
            Assert.assertEquals(view.newInfo.sourceFilePathDir, "java/lang/")
            Assert.assertEquals(view.newInfo.sourceFilePathFile, "String.java")
            Assert.assertNull(view.oldInfo)
            MatcherAssert.assertThat(view.alternatives, Matchers.hasItem(matches<SourceFileView.Alternative> {
                it.artifactId == "java/11" && it.sourceFilePath == "java.base/java/lang/String.java"
            }))
            tryRender(view)
        }
    }

    @Test
    fun diff() {
        dbi.inTransaction { conn, _ ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val java11 = artifactIndex.allArtifactsByStringId["java/11"]!!
            val view = handler.sourceFile(
                    conn,
                    BaseHandler.ParsedPath.SourceFile(java11, "java.base/java/lang/String.java"),
                    diffWith = BaseHandler.ParsedPath.SourceFile(java8, "java/lang/String.java")
            )
            Assert.assertEquals(view.newInfo.realm, Realm.SOURCE)
            Assert.assertEquals(view.newInfo.artifactId, java11)
            Assert.assertEquals(view.newInfo.classpath, setOf("java/11"))
            Assert.assertEquals(view.newInfo.sourceFilePathDir, "java.base/java/lang/")
            Assert.assertEquals(view.newInfo.sourceFilePathFile, "String.java")
            Assert.assertEquals(view.oldInfo?.realm, Realm.SOURCE)
            Assert.assertEquals(view.oldInfo?.artifactId, java8)
            Assert.assertEquals(view.oldInfo?.classpath, setOf("java/8"))
            Assert.assertEquals(view.oldInfo?.sourceFilePathDir, "java/lang/")
            Assert.assertEquals(view.oldInfo?.sourceFilePathFile, "String.java")
            tryRender(view)
        }
    }

    @Test
    fun leafArtifact() {
        dbi.inTransaction { conn, _ ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val java11 = artifactIndex.allArtifactsByStringId["java/11"]!!
            val view = handler.leafArtifact(
                    conn,
                    BaseHandler.ParsedPath.LeafArtifact(java11),
                    BaseHandler.ParsedPath.LeafArtifact(java8))
            Assert.assertEquals(view.artifactId, java11)
            Assert.assertEquals(view.oldArtifactId, java8)
            MatcherAssert.assertThat(
                    ImmutableList.copyOf(view.topLevelPackages),
                    Matchers.hasItem(matches<DeclarationNode> {
                        it.binding == "javax"
                    })
            )
            tryRender(view)
        }
    }
}