package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.base.Stopwatch
import com.google.common.collect.ImmutableList
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.skife.jdbi.v2.DBI
import org.testng.Assert
import org.testng.annotations.BeforeClass
import org.testng.annotations.Optional
import org.testng.annotations.Parameters
import org.testng.annotations.Test

class ReferenceResourceIntegrationTest {
    private lateinit var dbi: DBI
    private lateinit var artifactIndex: ArtifactIndex
    private lateinit var referenceResource: ReferenceResource

    @Parameters("dataSource")
    @BeforeClass
    fun setUp(@Optional dataSource: String?) {
        dbi = loadIntegrationTestDbi(dataSource)
        artifactIndex = ArtifactIndex(ArtifactUpdater(), dbi)
        referenceResource = ReferenceResource(dbi, ObjectMapper(), artifactIndex)
    }

    @Test
    fun `short filter`() {
        dbi.inTransaction { conn, _ ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val result = referenceResource.handleRequest(
                    conn,
                    Realm.SOURCE,
                    "java.util.Observable",
                    100,
                    java8
            )
            val returnType = result.find { it?.type == BindingRefType.PARAMETER_TYPE }!!
            val items = ImmutableList.copyOf(returnType.items)
            Assert.assertEquals(items.count { it.artifactId == "java/8" }, 1)
            Assert.assertEquals(items.count { it.artifactId.startsWith("java/") }, 1)
            val first = items[0]
            Assert.assertEquals(first.artifactId, "java/8")
        }
    }

    @Test
    fun `large filter`() {
        dbi.inTransaction { conn, _ ->
            val java8 = artifactIndex.allArtifactsByStringId["java/8"]!!
            val stopwatch = Stopwatch.createStarted()
            val result = referenceResource.handleRequest(
                    conn,
                    Realm.SOURCE,
                    "java.lang.String",
                    100,
                    java8
            )
            val returnType = result.find { it?.type == BindingRefType.PARAMETER_TYPE }!!
            val items = ImmutableList.copyOf(returnType.items)
            stopwatch.stop()
            println(stopwatch.elapsed())
            Assert.assertEquals(items.size, 100)
            MatcherAssert.assertThat(
                    items,
                    Matchers.not(Matchers.hasItem(matches<ReferenceResource.ResponseItem> {
                        it.artifactId != "java/8"
                    }))
            )
        }
    }
}