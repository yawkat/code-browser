package at.yawk.javabrowser.server

import io.dropwizard.Application
import io.dropwizard.jdbi.DBIFactory
import io.dropwizard.setup.Environment
import org.flywaydb.core.Flyway

/**
 * @author yawkat
 */
class Bootstrap : Application<Config>() {
    override fun run(configuration: Config, environment: Environment) {
        val dataSource = configuration.database.build(environment.metrics(), "h2")
        val flyway = Flyway()
        flyway.dataSource = dataSource
        flyway.migrate()
        val dbi = DBIFactory().build(environment, configuration.database, dataSource, "h2")

    }
}