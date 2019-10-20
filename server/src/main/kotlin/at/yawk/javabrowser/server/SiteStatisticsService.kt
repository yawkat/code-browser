package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.SiteStatistics
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.sqlobject.SqlQuery
import org.slf4j.LoggerFactory

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(SiteStatisticsService::class.java)

class SiteStatisticsService(
        private val dbi: DBI,
        artifactUpdater: ArtifactUpdater
) {
    private val debouncer = Debouncer()

    // loading takes a while, so initialize with 0.
    var statistics: SiteStatistics = SiteStatistics(0, 0, 0, 0, 0)
        private set

    init {
        artifactUpdater.addInvalidationListener(runAtStart = true) {
            debouncer.requestRun { statistics = loadStatistics() }
        }
    }

    private fun loadStatistics(): SiteStatistics = dbi.withHandle {
        log.info("Loading site statistics")
        val dao = it.attach(Dao::class.java)
        val siteStatistics = SiteStatistics(
                artifactCount = dao.getArtifactCount(),
                bindingCount = dao.getBindingCount(),
                classCount = dao.getClassCount(),
                referenceCount = dao.getReferenceCount(),
                sourceFileCount = dao.getSourceFileCount()
        )
        log.info("Loaded site statistics: {}", siteStatistics)
        siteStatistics
    }

    interface Dao {
        @SqlQuery("select count(*) from data.artifacts")
        fun getArtifactCount(): Long

        @SqlQuery("select count(*) from data.sourceFiles")
        fun getSourceFileCount(): Long

        @SqlQuery("select count(*) from data.bindings")
        fun getBindingCount(): Long

        @SqlQuery("select count(*) from data.bindings where isType")
        fun getClassCount(): Long

        @SqlQuery("select count(*) from data.binding_references")
        fun getReferenceCount(): Long
    }
}