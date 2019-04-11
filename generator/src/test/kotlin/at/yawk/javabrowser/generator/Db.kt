package at.yawk.javabrowser.generator

import at.yawk.javabrowser.DbConfig
import org.skife.jdbi.v2.DBI
import ru.yandex.qatools.embed.postgresql.EmbeddedPostgres

/**
 * @author yawkat
 */
fun createDb(): DBI {
    val embeddedPostgres = EmbeddedPostgres()
    val config = DbConfig(embeddedPostgres.start(), EmbeddedPostgres.DEFAULT_USER, EmbeddedPostgres.DEFAULT_PASSWORD)
    return config.start()
}