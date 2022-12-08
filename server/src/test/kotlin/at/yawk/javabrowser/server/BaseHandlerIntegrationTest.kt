package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.view.Alternative
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.DirectoryView
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.SingletonSupport
import com.google.common.collect.ImmutableList
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.jdbi.v3.core.Jdbi
import org.testng.Assert
import org.testng.annotations.BeforeClass
import org.testng.annotations.Optional
import org.testng.annotations.Parameters
import org.testng.annotations.Test

class BaseHandlerIntegrationTest {
    private lateinit var dbi: Jdbi
    private lateinit var artifactIndex: ArtifactIndex
    private lateinit var handler: BaseHandler
    private lateinit var directoryHandler: DirectoryHandler

    @Parameters("dataSource")
    @BeforeClass
    fun setUp(@Optional dataSource: String?) {
        dbi = loadIntegrationTestDbi(dataSource)
        val artifactUpdater = ArtifactUpdater()
        artifactIndex = ArtifactIndex(artifactUpdater, dbi)
        val ftl = Ftl(ImageCache())
        val objectMapper = ObjectMapper().registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))
        directoryHandler = DirectoryHandler(artifactIndex)
        handler = BaseHandler(
            dbi,
            ftl,
            BindingResolver(artifactUpdater, dbi, artifactIndex),
            artifactIndex,
            DeclarationTreeHandler(dbi, ftl, artifactIndex),
            SiteStatisticsService(dbi, artifactUpdater),
            AliasIndex(artifactUpdater, dbi),
            ArtifactMetadataCache(objectMapper, dbi, artifactUpdater),
            directoryHandler
        )
    }

    @Test
    fun `source file`() {
        dbi.inTransaction<Unit, Exception> { conn ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val view = handler.sourceFile(
                conn,
                realmOverride = null,
                parsedPath = ParsedPath.SourceFile(java8, "java/lang/String.java"),
                diffWith = null
            )!!
            Assert.assertEquals(view.newInfo.realm, Realm.SOURCE)
            Assert.assertEquals(view.newInfo.sourceFilePath.artifact, java8)
            Assert.assertEquals(view.newInfo.classpath, setOf("java/8"))
            Assert.assertEquals(view.newInfo.sourceFilePath.sourceFilePath, "java/lang/String.java")
            Assert.assertNull(view.oldInfo)
            MatcherAssert.assertThat(view.alternatives, Matchers.hasItem(matches<Alternative> {
                it.artifact.stringId == "java/11" && it.path == "/java/11/java.base/java/lang/String.java"
            }))
            tryRender(view)
        }
    }

    @Test
    fun diff() {
        dbi.inTransaction<Unit, Exception> { conn ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val java11 = artifactIndex.allArtifactsByStringId["java/11"]!!
            val view = handler.sourceFile(
                conn,
                realmOverride = null,
                parsedPath = ParsedPath.SourceFile(java11, "java.base/java/lang/String.java"),
                diffWith = ParsedPath.SourceFile(java8, "java/lang/String.java")
            )!!
            Assert.assertEquals(view.newInfo.realm, Realm.SOURCE)
            Assert.assertEquals(view.newInfo.sourceFilePath.artifact, java11)
            Assert.assertEquals(view.newInfo.classpath, setOf("java/11"))
            Assert.assertEquals(view.newInfo.sourceFilePath.sourceFilePath, "java.base/java/lang/String.java")
            Assert.assertEquals(view.oldInfo?.realm, Realm.SOURCE)
            Assert.assertEquals(view.oldInfo!!.sourceFilePath.artifact, java8)
            Assert.assertEquals(view.oldInfo?.classpath, setOf("java/8"))
            Assert.assertEquals(view.oldInfo!!.sourceFilePath.sourceFilePath, "java/lang/String.java")
            tryRender(view)
        }
    }

    @Test
    fun leafArtifact() {
        dbi.inTransaction<Unit, Exception> { conn ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val java11 = artifactIndex.allArtifactsByStringId["java/11"]!!
            val view = handler.leafArtifact(
                conn,
                ParsedPath.LeafArtifact(java11),
                ParsedPath.LeafArtifact(java8)
            )
            Assert.assertEquals(view.path.artifact, java11)
            Assert.assertEquals(view.oldPath!!.artifact, java8)
            MatcherAssert.assertThat(
                ImmutableList.copyOf(view.topLevelPackages),
                Matchers.hasItem(matches<DeclarationNode> {
                    it.binding == "javax"
                })
            )
            tryRender(view)
        }
    }

    @Test
    fun `directory entries`() {
        dbi.inTransaction<Unit, Exception> { conn ->
            val java11 = artifactIndex.allArtifactsByStringId["java/11"]!!
            val info = directoryHandler.getDirectoryEntries(
                conn,
                Realm.SOURCE,
                ParsedPath.SourceFile(java11, "java.base/java/lang/")
            )!!
            MatcherAssert.assertThat(
                info,
                Matchers.hasItem(matches<DirectoryHandler.DirectoryEntry> {
                    it.name == "String.java" &&
                            !it.isDirectory
                })
            )
            MatcherAssert.assertThat(
                info,
                Matchers.hasItem(matches<DirectoryHandler.DirectoryEntry> {
                    it.name == "reflect/" &&
                            it.isDirectory
                })
            )
        }
    }

    @Test
    fun `directory view`() {
        dbi.inTransaction<Unit, Exception> { conn ->
            val java11 = artifactIndex.allArtifactsByStringId["java/11"]!!
            val view = directoryHandler.directoryView(
                conn,
                null,
                ParsedPath.SourceFile(java11, "java.base/java/lang"),
                null
            )
            MatcherAssert.assertThat(
                view.alternatives,
                Matchers.hasItem(matches<Alternative> {
                    it.artifact.stringId == "java/12" && it.path == "/java/12/java.base/java/lang/" &&
                            it.diffPath == "/java/12/java.base/java/lang/?diff=%2Fjava%2F11%2Fjava.base%2Fjava%2Flang%2F"
                })
            )
            tryRender(view)
        }
    }

    @Test
    fun `directory view diff`() {
        dbi.inTransaction<Unit, Exception> { conn ->
            val java7 = artifactIndex.allArtifactsByStringId["java/7"]!!
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val view = directoryHandler.directoryView(
                conn,
                null,
                ParsedPath.SourceFile(java8, "java/lang"),
                ParsedPath.SourceFile(java7, "java/lang")
            )
            MatcherAssert.assertThat(
                view.entries,
                Matchers.hasItem(matches<DirectoryView.DirectoryEntry> {
                    it.name == "String.java" && !it.isDirectory &&
                            it.diffResult == DeclarationNode.DiffResult.CHANGED_INTERNALLY
                })
            )
            MatcherAssert.assertThat(
                view.entries,
                Matchers.hasItem(matches<DirectoryView.DirectoryEntry> {
                    it.name == "CharacterData00.java" && !it.isDirectory &&
                            it.diffResult == DeclarationNode.DiffResult.DELETION
                })
            )
            MatcherAssert.assertThat(
                view.entries,
                Matchers.hasItem(matches<DirectoryView.DirectoryEntry> {
                    it.name == "FunctionalInterface.java" && !it.isDirectory &&
                            it.diffResult == DeclarationNode.DiffResult.INSERTION
                })
            )
            tryRender(view)
        }
    }
}