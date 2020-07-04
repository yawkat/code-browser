package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.PositionedAnnotation
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.view.DeclarationNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.collect.ImmutableList
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.skife.jdbi.v2.DBI
import org.testng.Assert
import org.testng.annotations.BeforeClass
import org.testng.annotations.Optional
import org.testng.annotations.Parameters
import org.testng.annotations.Test

class DeclarationTreeHandlerIntegrationTest {
    private lateinit var dbi: DBI
    private lateinit var handler: DeclarationTreeHandler

    @Parameters("dataSource")
    @BeforeClass
    fun setUp(@Optional dataSource: String?) {
        dbi = loadIntegrationTestDbi(dataSource)
        handler = DeclarationTreeHandler(dbi, Ftl(ImageCache()), ArtifactIndex(ArtifactUpdater(), dbi))
    }

    @Test
    fun overview() {
        dbi.withHandle { handle ->
            val response = handler.handleRequest(
                    handle,
                    Realm.SOURCE,
                    artifactId = "java/8",
                    binding = "java.lang",
                    diffArtifactId = null
            )
            val children = ImmutableList.copyOf(response.children)
            Assert.assertNull(response.diffArtifactId)
            Assert.assertEquals(response.parentBinding, "java.lang")
            MatcherAssert.assertThat(
                    children,
                    Matchers.hasItem(matches<DeclarationNode> {
                        it.artifactId == "java/8" &&
                                !it.deprecated &&
                                it.binding == "java.lang.String" &&
                                it.description is BindingDecl.Description.Type
                    })
            )
            tryRender(response)
        }
    }

    @Test
    fun `no members at package level`() {
        dbi.withHandle { handle ->
            val response = handler.handleRequest(
                    handle,
                    Realm.SOURCE,
                    artifactId = "java/8",
                    binding = "java.lang.annotation",
                    diffArtifactId = null
            )
            val children = ImmutableList.copyOf(response.children)
            MatcherAssert.assertThat(
                    children,
                    Matchers.not(Matchers.hasItem(matches<DeclarationNode> {
                                it.description !is BindingDecl.Description.Type
                    }))
            )
            tryRender(response)
        }
    }

    @Test
    fun deprecated() {
        dbi.withHandle { handle ->
            val response = handler.handleRequest(
                    handle,
                    Realm.SOURCE,
                    artifactId = "java/8",
                    binding = "java.rmi.server",
                    diffArtifactId = null
            )
            val children = ImmutableList.copyOf(response.children)
            MatcherAssert.assertThat(
                    children,
                    Matchers.hasItem(matches<DeclarationNode> {
                        it.artifactId == "java/8" &&
                                it.deprecated &&
                                it.binding == "java.rmi.server.SocketSecurityException"
                    })
            )
            tryRender(response)
        }
    }

    @Test
    fun `source file`() {
        dbi.withHandle { handle ->
            val bytes = handle.select("select annotations from source_file natural join artifact where artifact.string_id = 'java/8' and source_file.path = 'java/lang/String.java'")[0]["annotations"] as ByteArray
            val annotations = cborMapper.readValue<List<PositionedAnnotation>>(bytes)
            val iterator = handler.sourceDeclarationTree(
                    Realm.SOURCE,
                    artifactId = "java/8",
                    annotations = annotations.asSequence(),
                    fullSourceFilePath = "java/lang/String.java"
            )
            val l2 = ImmutableList.copyOf(iterator.next().children!!)
            MatcherAssert.assertThat(
                    l2,
                    Matchers.hasItem(matches<DeclarationNode> {
                        it.binding == "java.lang.String#value" && it.artifactId == "java/8"
                    })
            )
            Assert.assertFalse(iterator.hasNext())
        }
    }

    @Test
    fun `module-info`() {
        dbi.withHandle { handle ->
            val bytes = handle.select("select annotations from source_file natural join artifact where artifact.string_id = 'java/14' and source_file.path = 'jdk.crypto.cryptoki/module-info.java'")[0]["annotations"] as ByteArray
            val annotations = cborMapper.readValue<List<PositionedAnnotation>>(bytes)
            val iterator = handler.sourceDeclarationTree(
                    Realm.SOURCE,
                    artifactId = "java/14",
                    annotations = annotations.asSequence(),
                    fullSourceFilePath = "jdk.crypto.cryptoki/module-info.java"
            )
            // consume
            iterator.forEach { _ -> }
        }
    }
}