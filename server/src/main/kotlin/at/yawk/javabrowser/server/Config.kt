package at.yawk.javabrowser.server

import at.yawk.javabrowser.DbConfig

/**
 * @author yawkat
 */
data class Config(
        val database: DbConfig,
        val bindAddress: String,
        val bindPort: Int,
        val typeIndexChunkSize: Int
)