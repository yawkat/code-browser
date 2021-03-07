package at.yawk.javabrowser.generator

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.transfer.ArtifactNotFoundException
import org.jboss.shrinkwrap.resolver.api.NoResolvedResultException
import org.jboss.shrinkwrap.resolver.api.maven.Maven
import org.jboss.shrinkwrap.resolver.api.maven.MavenArtifactInfo
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType
import org.jboss.shrinkwrap.resolver.api.maven.ScopeType
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependencies
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependency
import org.jboss.shrinkwrap.resolver.api.maven.filter.MavenResolutionFilter
import org.jboss.shrinkwrap.resolver.api.maven.strategy.MavenResolutionStrategy
import org.jboss.shrinkwrap.resolver.api.maven.strategy.TransitiveExclusionPolicy
import org.slf4j.bridge.SLF4JBridgeHandler

/**
 * @author yawkat
 */
class MavenDependencyResolver(val config: Config = Config()) {
    private companion object {
        init {
            SLF4JBridgeHandler.removeHandlersForRootLogger()
            SLF4JBridgeHandler.install()
        }
    }

    fun getMavenDependencies(groupId: String, artifactId: String, version: String): List<MavenArtifactInfo> {
        // okay, I give up. Maintaining a manual exclusion list would be too much effort. Instead, we simply retry
        // during resolving and exclude any artifacts we can't find.
        val dynamicExclusions = ArrayList<Artifact>()
        retry@ while (true) {
            try {
                val resolver = Maven.configureResolver()
                        .withMavenCentralRepo(true)
                val s1 = resolver.addDependency(MavenDependencies.createDependency(
                        MavenCoordinates.createCoordinate(
                                groupId, artifactId, version,
                                PackagingType.JAR, null),
                        ScopeType.COMPILE,
                        false
                )).resolve().using(MavenResolutionStrategyImpl(MavenResolutionFilterImpl(dynamicExclusions)))
                return s1.asResolvedArtifact().asList()
            } catch (e: NoResolvedResultException) {
                var cause: Throwable? = e
                while (cause != null) {
                    if (cause is ArtifactNotFoundException && !dynamicExclusions.contains(cause.artifact)) {
                        dynamicExclusions.add(cause.artifact)
                        continue@retry
                    }
                    cause = cause.cause
                }
                throw e
            }
        }
    }

    data class Config(
            // MavenDependencyExclusion doesn't allow versions, so we use MavenCoordinate
            val excludeDependencies: List<MavenCoordinate> = emptyList()
    )

    private inner class MavenResolutionFilterImpl(
            private val dynamicExclusions: List<Artifact>
    ) : MavenResolutionFilter {
        override fun accepts(dependency: MavenDependency,
                             dependenciesForResolution: List<MavenDependency>,
                             dependencyAncestors: List<MavenDependency>): Boolean {
            // dynamic dependencies
            if (dependency.version.contains("\${") || dependency.classifier.contains("\${")) return false
            // don't include transitive optional dependencies
            //if (dependency.isOptional && dependencyAncestors.size > 1) return false

            for (exclusion in config.excludeDependencies) {
                if (exclusion.groupId == dependency.groupId &&
                        exclusion.artifactId == dependency.artifactId &&
                        (exclusion.version == null || exclusion.version == dependency.version) &&
                        (exclusion.classifier == null || exclusion.classifier == dependency.classifier) &&
                        exclusion.packaging == dependency.packaging) {
                    return false
                }
            }

            for (dynamicExclusion in dynamicExclusions) {
                if (dynamicExclusion.groupId == dependency.groupId &&
                        dynamicExclusion.artifactId == dependency.artifactId &&
                        dynamicExclusion.version == dependency.version &&
                        dynamicExclusion.classifier == dependency.classifier &&
                        dynamicExclusion.extension == dependency.packaging.extension) {
                    return false
                }
            }

            return true
        }
    }

    private class MavenResolutionStrategyImpl(private val filter: MavenResolutionFilter) : MavenResolutionStrategy {
        override fun getTransitiveExclusionPolicy() = TransitiveExclusionPolicyImpl
        override fun getResolutionFilters() = arrayOf(filter)
    }

    private object TransitiveExclusionPolicyImpl : TransitiveExclusionPolicy {
        // we'd like 'provided' as well but that breaks things. (e.g. org.apache.logging.log4j:log4j-core:2.11.0)
        override fun getFilteredScopes() = arrayOf(ScopeType.TEST, ScopeType.PROVIDED)

        override fun allowOptional() = true
    }
}