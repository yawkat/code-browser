package org.jboss.shrinkwrap.resolver.impl.maven.bootstrap

import org.apache.maven.wagon.AbstractWagon
import org.apache.maven.wagon.ResourceDoesNotExistException
import org.apache.maven.wagon.Wagon
import org.apache.maven.wagon.authentication.AuthenticationInfo
import org.apache.maven.wagon.authorization.AuthorizationException
import org.apache.maven.wagon.providers.http.LightweightHttpWagon
import org.apache.maven.wagon.providers.http.LightweightHttpWagonAuthenticator
import org.apache.maven.wagon.providers.http.LightweightHttpsWagon
import org.apache.maven.wagon.proxy.ProxyInfoProvider
import org.apache.maven.wagon.repository.Repository
import org.eclipse.aether.transport.wagon.WagonProvider
import org.jboss.shrinkwrap.resolver.api.ResolutionException
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Field
import java.security.AccessController
import java.security.PrivilegedActionException
import java.security.PrivilegedExceptionAction

/**
 * Supports mock s3 wagon on top of the normal shrinkwrap wagons
 */
private val log = LoggerFactory.getLogger(
        "at.yawk.javabrowser.generator.org.jboss.shrinkwrap.resolver.impl.maven.bootstrap.ManualWagonProvider")

@Suppress("unused")
class ManualWagonProvider : WagonProvider {
    @Throws(Exception::class)
    override fun lookup(roleHint: String): Wagon {
        if (roleHint == "http" || roleHint == "https") {
            return setAuthenticator(CentralOnlyWagonHttps())
        } else if (roleHint == "file") {
            throw Exception("unsupported")
        } else if (roleHint == "s3") {
            return EmptyWagon
        }

        throw UnsupportedOperationException(roleHint)
    }

    override fun release(wagon: Wagon) {
    }

    private companion object {
        fun checkRepo(repository: Repository) {
            if (repository.host != "repo1.maven.org" && repository.host != "repo.maven.apache.org") {
                log.debug("Denying access to repository on host {}", repository.host)
                throw ResourceDoesNotExistException("Only central allowed")
            }
        }
    }

    private class CentralOnlyWagonHttps : LightweightHttpsWagon() {
        override fun openConnectionInternal() {
            checkRepo(repository)
            super.openConnectionInternal()
        }

        override fun connect(repository: Repository,
                             authenticationInfo: AuthenticationInfo?,
                             proxyInfoProvider: ProxyInfoProvider?) {
            checkRepo(repository)
            // central does not support http anymore
            // can't just set the protocol field because the url field may already have been set
            if (repository.url.startsWith("http:")) {
                repository.url = "https" + repository.url.removePrefix("http")
            }
            super.connect(repository, authenticationInfo, proxyInfoProvider)
        }
    }

    // from shrinkwrap

    // SHRINKRES-68
    // Wagon noes not correctly fill Authenticator field if Plexus is not used
    // we need to use reflexion in order to get fix this behavior
    // http://dev.eclipse.org/mhonarc/lists/aether-users/msg00113.html
    private fun setAuthenticator(wagon: LightweightHttpWagon): LightweightHttpWagon {
        val authenticator: Field
        try {
            authenticator = AccessController.doPrivileged(PrivilegedExceptionAction {
                val field = LightweightHttpWagon::class.java.getDeclaredField("authenticator")
                field.isAccessible = true
                field
            })
        } catch (pae: PrivilegedActionException) {
            throw ResolutionException("Could not manually set authenticator to accessible on " + LightweightHttpWagon::class.java.name,
                    pae)
        }

        try {
            authenticator.set(wagon, LightweightHttpWagonAuthenticator())
        } catch (e: Exception) {
            throw ResolutionException("Could not manually set authenticator on " + LightweightHttpWagon::class.java.name,
                    e)
        }

        // SHRINKRES-69
        // Needed to ensure that we do not cache BASIC Auth values
        wagon.setPreemptiveAuthentication(true)

        return wagon
    }

    private object EmptyWagon : AbstractWagon() {
        override fun closeConnection() {
        }

        override fun getIfNewer(resourceName: String?, destination: File?, timestamp: Long): Boolean {
            throw ResourceDoesNotExistException("empty wagon")
        }

        override fun get(resourceName: String?, destination: File?) {
            throw ResourceDoesNotExistException("empty wagon")
        }

        override fun put(source: File?, destination: String?) {
            throw AuthorizationException("unsupported")
        }

        override fun openConnectionInternal() {
        }
    }
}
