package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.view.FullTextSearchResultView
import com.google.common.collect.ImmutableList
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.jdbi.v3.core.Jdbi
import org.testng.annotations.BeforeClass
import org.testng.annotations.Optional
import org.testng.annotations.Parameters
import org.testng.annotations.Test

class FullTextSearchResourceIntegrationTest {
    private lateinit var dbi: Jdbi
    private lateinit var artifactIndex: ArtifactIndex
    private lateinit var fts: FullTextSearchResource

    @Parameters("dataSource")
    @BeforeClass
    fun setUp(@Optional dataSource: String?) {
        dbi = loadIntegrationTestDbi(dataSource)
        artifactIndex = ArtifactIndex(ArtifactUpdater(), dbi)
        fts = FullTextSearchResource(dbi, Ftl(ImageCache()), BindingResolver(ArtifactUpdater(), dbi, artifactIndex), artifactIndex)
    }

    @Test
    fun string() {
        dbi.withHandle<Unit, Exception> { conn ->
            val view = fts.handleRequest(
                    query = "count is negative",
                    realm = Realm.SOURCE,
                    searchArtifact = null,
                    useSymbolsParameter = null,
                    conn = conn
            )
            val results = ImmutableList.copyOf(view.results)
            MatcherAssert.assertThat(
                    results,
                    Matchers.hasItem(matches<FullTextSearchResultView.SourceFileResult> {
                        it.artifactId == "java/8" &&
                                it.path == "java/lang/String.java"
                    })
            )
            conn.rollback()
        }
    }

    @Test
    fun `string try render`() {
        dbi.withHandle<Unit, Exception> { conn ->
            val view = fts.handleRequest(
                    query = "count is negative",
                    realm = Realm.SOURCE,
                    searchArtifact = null,
                    useSymbolsParameter = null,
                    conn = conn
            )
            tryRender(view)
            conn.rollback()
        }
    }

    @Test
    fun symbols() {
        dbi.withHandle<Unit, Exception> { conn ->
            val view = fts.handleRequest(
                    query = "count < 0",
                    realm = Realm.SOURCE,
                    searchArtifact = artifactIndex.allArtifactsByStringId["java/8"],
                    useSymbolsParameter = null,
                    conn = conn
            )
            val results = ImmutableList.copyOf(view.results)
            MatcherAssert.assertThat(
                    results,
                    Matchers.hasItem(matches<FullTextSearchResultView.SourceFileResult> {
                        it.artifactId == "java/8" &&
                                it.path == "java/lang/String.java"
                    })
            )
            conn.rollback()
        }
    }
}