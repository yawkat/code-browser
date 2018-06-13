package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.IndexView
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.server.model.Resource
import javax.ws.rs.HttpMethod
import javax.ws.rs.core.MediaType

/**
 * @author yawkat
 */
class Overview(private val artifactIds: List<String>) {
    fun registerOverviewResources(config: ResourceConfig) {
        val groups = LinkedHashSet<ArtifactId.Component>()
        for (artifactId in artifactIds) {
            for (component in ArtifactId.split(artifactId).components) {
                if (component.fullPath != artifactId) {
                    groups.add(component)
                }
            }
        }

        for (group in groups) {
            val prefix = if (group.fullPath.isEmpty()) "" else group.fullPath
            val versions = ArrayList<String>()
            val children = ArrayList<String>()
            for (artifactId in artifactIds) {
                if (artifactId.startsWith(prefix)) {
                    val relative = artifactId.removePrefix(prefix)
                    val slashIndex = relative.indexOf('/')
                    if (slashIndex == -1) {
                        versions.add(relative)
                    } else {
                        val sub = relative.substring(0, slashIndex)
                        if (sub !in children) {
                            children.add(sub)
                        }
                    }
                }
            }
            versions.sortWith(VersionComparator)
            children.sortWith(String.CASE_INSENSITIVE_ORDER)
            val parents = groups.filter { group.fullPath.startsWith(it.fullPath) }

            val builder = Resource.builder(group.fullPath)

            builder.addMethod(HttpMethod.GET)
                    .produces(MediaType.TEXT_HTML)
                    .handledBy { _ -> IndexView(ArtifactId(prefix, parents), prefix, versions, children) }

            config.registerResources(builder.build())
        }
    }
}