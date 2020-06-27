package at.yawk.javabrowser.server

import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Multimap
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AliasIndex @Inject constructor(
        artifactUpdater: ArtifactUpdater,
        dbi: DBI
) {
    @Volatile
    private var aliases: Multimap<Long, String> = ArrayListMultimap.create()

    init {
        artifactUpdater.addInvalidationListener(true) {
            dbi.inTransaction { conn: Handle, _ ->
                val newAliases = ArrayListMultimap.create<Long, String>()
                for (row in conn.select("select artifact_id, alias from artifact_alias")) {
                    newAliases.put((row["artifact_id"] as Number).toLong(), row["alias"] as String)
                }
                this.aliases = newAliases
            }
        }
    }

    /**
     * Find an artifact that is aliased to the requested ID or an ID where only the version differs.
     */
    fun findAliasedTo(requestedId: String): Long? {
        for ((actual, alias) in aliases.entries()) {
            if (alias == requestedId) {
                return actual
            }
        }

        val prefixWithoutId = requestedId.substring(0, requestedId.lastIndexOf('/') + 1)
        for ((actual, alias) in aliases.entries()) {
            if (alias.startsWith(prefixWithoutId)) {
                return actual
            }
        }

        return null
    }
}