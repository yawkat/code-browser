package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.SiteStatistics
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.sqlobject.statement.SqlQuery
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(SiteStatisticsService::class.java)

@Singleton
class SiteStatisticsService @Inject constructor(
        private val dbi: Jdbi,
        artifactUpdater: ArtifactUpdater
) {
    private val debouncer = Debouncer()

    // loading takes a while, so initialize with 0.
    var statistics: SiteStatistics = SiteStatistics(0, 0, 0, 0, 0, 0, 0)
        private set

    init {
        artifactUpdater.addInvalidationListener(runAtStart = true) {
            debouncer.requestRun { statistics = loadStatistics() }
        }
    }

    private fun loadStatistics(): SiteStatistics = dbi.withHandle<SiteStatistics, Exception> {
        log.info("Loading site statistics")
        val dao = it.attach(Dao::class.java)
        val siteStatistics = SiteStatistics(
                artifactCount = dao.getArtifactCount(),
                bindingCount = dao.getBindingCount(),
                classCount = dao.getClassCount(),
                referenceCount = dao.getReferenceCount(),
                sourceFileCount = dao.getSourceFileCount(),
                lexemeCountNoSymbols = dao.getLexemeCountNoSymbols(),
                lexemeCountWithSymbols = dao.getLexemeCountWithSymbols()
        )
        log.info("Loaded site statistics: {}", siteStatistics)
        siteStatistics
    }

    interface Dao {
        @SqlQuery("select count(*) from artifact")
        fun getArtifactCount(): Long

        @SqlQuery("select count(*) from source_file")
        fun getSourceFileCount(): Long

        @SqlQuery("select count(*) from binding")
        fun getBindingCount(): Long

        @SqlQuery("select count(*) from binding where include_in_type_search")
        fun getClassCount(): Long

        @SqlQuery("select count(*) from binding_reference")
        fun getReferenceCount(): Long

        @SqlQuery("select sum(array_length(starts, 1)) from source_file_lexemes_no_symbols")
        fun getLexemeCountNoSymbols(): Long

        @SqlQuery("select sum(array_length(starts, 1)) from source_file_lexemes")
        fun getLexemeCountWithSymbols(): Long
    }
}