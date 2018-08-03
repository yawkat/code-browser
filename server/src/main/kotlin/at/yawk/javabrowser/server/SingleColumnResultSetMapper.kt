package at.yawk.javabrowser.server

import org.skife.jdbi.v2.tweak.ResultSetMapper

/**
 * @author yawkat
 */
object SingleColumnResultSetMapper {
    val STRING = ResultSetMapper { _, r, _ -> r.getString(1) }
    val INT = ResultSetMapper { _, r, _ -> r.getInt(1) }
}