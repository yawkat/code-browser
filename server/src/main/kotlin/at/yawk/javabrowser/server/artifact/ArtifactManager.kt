package at.yawk.javabrowser.server.artifact

import at.yawk.javabrowser.server.ArtifactConfig

/**
 * @author yawkat
 */
class ArtifactManager {
    private val root = ArtifactGroup("", parent = null)

    private val groupsByPrefix = mutableMapOf("" to root)
    private val _artifacts = mutableMapOf<String, Artifact>()
    val artifacts
        get() = _artifacts.values

    private fun getParentGroup(memberId: String) = getParentGroup(memberId, true)!!

    private fun getParentPrefix(memberId: String): String? {
        val lastSlash = memberId.lastIndexOf('/')
        if (lastSlash == -1) {
            return null
        } else {
            return memberId.substring(0, lastSlash + 1)
        }
    }

    private fun getParentGroup(memberId: String, create: Boolean): ArtifactGroup? {
        val prefix = getParentPrefix(memberId) ?: return root
        return if (create) {
            groupsByPrefix.computeIfAbsent(prefix) {
                val prefixNoSlash = prefix.removeSuffix("/")
                val parent = getParentGroup(prefixNoSlash)
                val computed = ArtifactGroup(prefix, parent)
                parent.childGroups.add(computed)
                computed
            }
        } else {
            groupsByPrefix[prefix]
        }
    }

    fun init(configs: List<ArtifactConfig>) {
        for (config in configs) {
            addArtifact(config)
        }
        bake()
    }

    private fun addArtifact(config: ArtifactConfig) {
        val id = when (config) {
            is ArtifactConfig.OldJava -> "java/${config.version}"
            is ArtifactConfig.Java -> "java/${config.version}"
            is ArtifactConfig.Maven -> "${config.groupId}/${config.artifactId}/${config.version}"
        }

        val parentGroup = getParentGroup(id)
        val artifact = Artifact(id, config, parentGroup)
        parentGroup.childArtifacts.add(artifact)
        _artifacts[id] = artifact
    }

    private fun bake() {
        for (group in groupsByPrefix.values) {
            group.childArtifacts.sortByDescending { it.version }
        }
    }

    fun getArtifact(artifactId: String) = _artifacts[artifactId] ?: throw NoSuchElementException()

    fun findClosestMatch(artifactId: String): Artifact? {
        _artifacts[artifactId]?.let { return it }
        val parentGroup = getParentGroup(artifactId, create = false) ?: return null
        val requestVersion = Version(artifactId.removePrefix(parentGroup.prefix))
        val order = parentGroup.childArtifacts.sortedByDescending { it.version.matchingPrefixLength(requestVersion) }
        return order.firstOrNull()
    }

    fun findClosestPrefix(artifactId: String): String? {
        _artifacts[artifactId]?.let { return it.id }
        var prefix: String? = artifactId
        while (prefix != null) {
            groupsByPrefix[prefix]?.let { return it.prefix }
            prefix = getParentPrefix(prefix.removeSuffix("/"))
        }
        return null
    }
}