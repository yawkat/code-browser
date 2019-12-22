package at.yawk.javabrowser.server

import at.yawk.javabrowser.DbConfig
import java.nio.file.Path

/**
 * @author yawkat
 */
data class Config(
        val database: DbConfig,
        val bindAddress: String,
        val bindPort: Int,
        val typeIndexChunkSize: Int = 32,
        val typeIndexDirectory: Path? = null
)