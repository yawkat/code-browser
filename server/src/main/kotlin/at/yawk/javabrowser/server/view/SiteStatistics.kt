package at.yawk.javabrowser.server.view

/**
 * @author yawkat
 */
data class SiteStatistics(
        val artifactCount: Long,
        val sourceFileCount: Long,
        val classCount: Long,
        val bindingCount: Long,
        val referenceCount: Long,
        val lexemeCountNoSymbols: Long,
        val lexemeCountWithSymbols: Long
)