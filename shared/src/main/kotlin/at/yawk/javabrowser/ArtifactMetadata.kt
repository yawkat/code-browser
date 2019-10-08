package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * @author yawkat
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactMetadata(
        val logoUrl: String? = null,
        val licenses: List<License>? = null,
        val url: String? = null,
        val description: String? = null,
        val issueTracker: IssueTracker? = null,
        val organization: Organization? = null,
        val developers: List<Developer>? = null,
        val contributors: List<Developer>? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class License(
            val name: String,
            val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Organization(
            val name: String? = null,
            val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Developer(
            val name: String? = null,
            val email: String? = null,
            val url: String? = null,
            val organization: Organization? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IssueTracker(
            val type: String? = null,
            val url: String? = null
    )
}