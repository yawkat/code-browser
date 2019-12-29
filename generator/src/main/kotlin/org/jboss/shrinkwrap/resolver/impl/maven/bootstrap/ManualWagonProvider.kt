package org.jboss.shrinkwrap.resolver.impl.maven.bootstrap

import org.apache.maven.wagon.AbstractWagon
import org.apache.maven.wagon.ResourceDoesNotExistException
import org.apache.maven.wagon.Wagon
import org.apache.maven.wagon.authorization.AuthorizationException
import org.apache.maven.wagon.providers.http.LightweightHttpWagon
import org.apache.maven.wagon.providers.http.LightweightHttpWagonAuthenticator
import org.apache.maven.wagon.providers.http.LightweightHttpsWagon
import org.apache.maven.wagon.repository.Repository
import org.eclipse.aether.transport.wagon.WagonProvider
import org.jboss.shrinkwrap.resolver.api.ResolutionException
import java.io.File
import java.lang.reflect.Field
import java.security.AccessController
import java.security.PrivilegedActionException
import java.security.PrivilegedExceptionAction

/**
 * Supports mock s3 wagon on top of the normal shrinkwrap wagons
 */
@Suppress("unused")
class ManualWagonProvider : WagonProvider {
    @Throws(Exception::class)
    override fun lookup(roleHint: String): Wagon {
        if (roleHint == "http") {
            return setAuthenticator(CentralOnlyWagonHttp())
        } else if (roleHint == "https") {
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
            if (repository.host != "repo1.maven.org") {
                throw ResourceDoesNotExistException("Only central allowed")
            }
        }
    }

    private class CentralOnlyWagonHttp : LightweightHttpWagon() {
        override fun openConnectionInternal() {
            checkRepo(repository)
            super.openConnectionInternal()
        }
    }

    private class CentralOnlyWagonHttps : LightweightHttpsWagon() {
        override fun openConnectionInternal() {
            checkRepo(repository)
            super.openConnectionInternal()
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
