package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.view.ReferenceDetailView
import com.google.common.collect.ImmutableList
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.testng.Assert
import org.testng.annotations.BeforeClass
import org.testng.annotations.Optional
import org.testng.annotations.Parameters
import org.testng.annotations.Test

inline fun <T : Any?> Iterator<T>.find(predicate: (T) -> Boolean): T {
    while (hasNext()) {
        val t = next()
        if (predicate(t)) return t
    }
    throw NoSuchElementException()
}

class ReferenceDetailResourceIntegrationTest {
    private lateinit var dbi: DBI
    private lateinit var artifactIndex: ArtifactIndex
    private lateinit var referenceDetailResource: ReferenceDetailResource

    @Parameters("dataSource")
    @BeforeClass
    fun setUp(@Optional dataSource: String?) {
        dbi = loadIntegrationTestDbi(dataSource)
        artifactIndex = ArtifactIndex(ArtifactUpdater(), dbi)
        referenceDetailResource = ReferenceDetailResource(dbi, Ftl(ImageCache()), artifactIndex)
    }

    @Test
    fun full() {
        dbi.inTransaction { conn: Handle, _ ->
            val view = referenceDetailResource.handleRequest(
                    conn,
                    Realm.SOURCE,
                    targetBinding = "java.lang.String#repeat(int)",
                    type = null,
                    limit = null,
                    sourceArtifact = null
            )
            Assert.assertEquals(view.realm, Realm.SOURCE)
            Assert.assertEquals(view.targetBinding, "java.lang.String#repeat(int)")
            Assert.assertNull(view.type)
            Assert.assertNull(view.sourceArtifactId)
            Assert.assertNull(view.resultLimit)

            Assert.assertEquals(view.countTable["java/12", BindingRefType.METHOD_CALL], 3)
            MatcherAssert.assertThat(view.countsByType[BindingRefType.METHOD_CALL]!!, Matchers.greaterThanOrEqualTo(13))
            Assert.assertEquals(view.countsByArtifact["java/12"], 3)
            MatcherAssert.assertThat(view.totalCount, Matchers.greaterThanOrEqualTo(13))

            Assert.assertNull(view.resultLimit)
            Assert.assertFalse(view.hitResultLimit) // we set the limit to null
            MatcherAssert.assertThat(view.totalCountInSelection, Matchers.greaterThanOrEqualTo(13))

            val methodCall = view.results.find { it?.type == BindingRefType.METHOD_CALL }!!.artifacts
            val methodCallJ12 = ImmutableList.copyOf(methodCall.find { it?.artifactId == "java/12" }!!.sourceFiles)
            MatcherAssert.assertThat(methodCallJ12, Matchers.hasItem(matches<ReferenceDetailView.SourceFileListing> {
                it.sourceFile == "java.base/java/lang/constant/ClassDesc.java" && it.items.any { row ->
                    row.sourceFileLine == 180
                }
            }))
        }
    }

    @Test
    fun `full render`() {
        dbi.inTransaction { conn: Handle, _ ->
            val view = referenceDetailResource.handleRequest(
                    conn,
                    Realm.SOURCE,
                    targetBinding = "java.lang.String#repeat(int)",
                    type = null,
                    limit = null,
                    sourceArtifact = null
            )
            tryRender(view)
        }
    }

    @Test
    fun `filter type`() {
        dbi.inTransaction { conn: Handle, _ ->
            val view = referenceDetailResource.handleRequest(
                    conn,
                    Realm.SOURCE,
                    targetBinding = "java.lang.String#repeat(int)",
                    type = BindingRefType.METHOD_CALL,
                    limit = null,
                    sourceArtifact = null
            )
            Assert.assertNull(view.resultLimit)
            Assert.assertFalse(view.hitResultLimit) // we set the limit to null
            MatcherAssert.assertThat(view.totalCountInSelection, Matchers.greaterThanOrEqualTo(13))

            val methodCall = view.results.find { it?.type == BindingRefType.METHOD_CALL }!!.artifacts
            val methodCallJ12 = ImmutableList.copyOf(methodCall.find { it?.artifactId == "java/12" }!!.sourceFiles)
            MatcherAssert.assertThat(methodCallJ12, Matchers.hasItem(matches<ReferenceDetailView.SourceFileListing> {
                it.sourceFile == "java.base/java/lang/constant/ClassDesc.java" && it.items.any { row ->
                    row.sourceFileLine == 180
                }
            }))
        }
    }

    @Test
    fun `filter artifact`() {
        dbi.inTransaction { conn: Handle, _ ->
            val view = referenceDetailResource.handleRequest(
                    conn,
                    Realm.SOURCE,
                    targetBinding = "java.lang.String#repeat(int)",
                    type = null,
                    limit = null,
                    sourceArtifact = artifactIndex.allArtifactsByStringId["java/12"]
            )
            Assert.assertNull(view.resultLimit)
            Assert.assertFalse(view.hitResultLimit) // we set the limit to null
            Assert.assertEquals(view.totalCountInSelection, 3)

            val methodCall = view.results.next()
            Assert.assertEquals(methodCall!!.type, BindingRefType.METHOD_CALL)
            val methodCallJ12 = methodCall.artifacts.next()
            Assert.assertEquals(methodCallJ12!!.artifactId, "java/12")
            val results = ImmutableList.copyOf(methodCallJ12.sourceFiles)
            MatcherAssert.assertThat(results, Matchers.hasItem(matches<ReferenceDetailView.SourceFileListing> {
                it.sourceFile == "java.base/java/lang/constant/ClassDesc.java" && it.items.any { row ->
                    row.sourceFileLine == 180
                }
            }))
            Assert.assertFalse(methodCall.artifacts.hasNext())
            Assert.assertFalse(view.results.hasNext())
        }
    }

    @Test
    fun limit() {
        dbi.inTransaction { conn: Handle, _ ->
            val view = referenceDetailResource.handleRequest(
                    conn,
                    Realm.SOURCE,
                    targetBinding = "java.lang.String#repeat(int)",
                    type = null,
                    limit = 1,
                    sourceArtifact = artifactIndex.allArtifactsByStringId["java/12"]
            )
            Assert.assertEquals(view.resultLimit, 1)
            Assert.assertTrue(view.hitResultLimit)
            Assert.assertEquals(view.totalCountInSelection, 3)

            val methodCall = view.results.next()!!.artifacts
            val methodCallJ12 = ImmutableList.copyOf(methodCall.next()!!.sourceFiles)
            MatcherAssert.assertThat(methodCallJ12, Matchers.hasItem(matches<ReferenceDetailView.SourceFileListing> {
                it.sourceFile == "java.base/java/lang/constant/ClassDesc.java" && it.items.any { row ->
                    row.sourceFileLine == 180
                }
            }))
        }
    }
}