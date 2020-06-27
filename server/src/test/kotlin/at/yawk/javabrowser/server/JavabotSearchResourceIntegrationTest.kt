package at.yawk.javabrowser.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.testng.annotations.BeforeClass
import org.testng.annotations.Optional
import org.testng.annotations.Parameters
import org.testng.annotations.Test

class JavabotSearchResourceIntegrationTest {
    private lateinit var resource: JavabotSearchResource

    @Parameters("dataSource")
    @BeforeClass
    fun setUp(@Optional dataSource: String?) {
        val dbi = loadIntegrationTestDbi(dataSource)
        resource = JavabotSearchResource(dbi, ObjectMapper(), ArtifactIndex(ArtifactUpdater(), dbi))
    }

    private val javaLangString = matches<JavabotSearchResource.ResultRow> {
        it.artifactId.startsWith("java/") &&
                it.binding == "java.lang.String" &&
                it.sourceFile == "java.base/java/lang/String.java"
    }

    @Test
    fun `simple class name`() {
        MatcherAssert.assertThat(
                resource.handleRequest("String", artifact = null),
                Matchers.hasItem(javaLangString)
        )
    }

    @Test
    fun `qualified class name`() {
        MatcherAssert.assertThat(
                resource.handleRequest("java.lang.String", artifact = null).single(),
                javaLangString
        )
    }

    private val javaLangStringValue = matches<JavabotSearchResource.ResultRow> {
        it.artifactId.startsWith("java/") &&
                it.binding == "java.lang.String#value" &&
                it.sourceFile == "java.base/java/lang/String.java"
    }

    @Test
    fun `simple field name`() {
        MatcherAssert.assertThat(
                resource.handleRequest("String.value", artifact = null),
                Matchers.hasItem(javaLangStringValue)
        )
    }

    @Test
    fun `qualified field name`() {
        MatcherAssert.assertThat(
                resource.handleRequest("java.lang.String.value", artifact = null).single(),
                javaLangStringValue
        )
    }

    @Test
    fun `qualified field name hash`() {
        MatcherAssert.assertThat(
                resource.handleRequest("java.lang.String#value", artifact = null).single(),
                javaLangStringValue
        )
    }

    private val javaLangStringEquals = matches<JavabotSearchResource.ResultRow> {
        it.artifactId.startsWith("java/") &&
                it.binding == "java.lang.String#equals(java.lang.Object)" &&
                it.sourceFile == "java.base/java/lang/String.java"
    }

    @Test
    fun `qualified method`() {
        MatcherAssert.assertThat(
                resource.handleRequest("java.lang.String.equals(*)", artifact = null).single(),
                javaLangStringEquals
        )
    }

    @Test
    fun `simple method`() {
        MatcherAssert.assertThat(
                resource.handleRequest("String.equals(*)", artifact = null),
                Matchers.hasItem(javaLangStringEquals)
        )
    }

    @Test
    fun `qualified method explicit qualified param`() {
        MatcherAssert.assertThat(
                resource.handleRequest("java.lang.String.equals(java.lang.Object)", artifact = null).single(),
                javaLangStringEquals
        )
    }

    @Test
    fun `qualified method explicit simple param`() {
        MatcherAssert.assertThat(
                resource.handleRequest("java.lang.String.equals(Object)", artifact = null).single(),
                javaLangStringEquals
        )
    }

    @Test
    fun `qualified method primitive`() {
        MatcherAssert.assertThat(
                resource.handleRequest("java.lang.String.indexOf(int, int)", artifact = null).single(),
                matches<JavabotSearchResource.ResultRow> {
                    it.artifactId.startsWith("java/") &&
                            it.binding == "java.lang.String#indexOf(int,int)" &&
                            it.sourceFile == "java.base/java/lang/String.java"
                }
        )
    }

    @Test
    fun `artifact selector`() {
        MatcherAssert.assertThat(
                resource.handleRequest("String", artifact = "java"),
                Matchers.hasItem(javaLangString)
        )
    }

    @Test
    fun `artifact selector with version`() {
        MatcherAssert.assertThat(
                resource.handleRequest("String", artifact = "java/8"),
                Matchers.hasItem(matches<JavabotSearchResource.ResultRow> {
                    it.artifactId == "java/8" &&
                            it.binding == "java.lang.String" &&
                            it.sourceFile == "java/lang/String.java"
                })
        )
    }

    @Test
    fun `artifact selector negate`() {
        MatcherAssert.assertThat(
                resource.handleRequest("String", artifact = "guava"),
                Matchers.not(Matchers.hasItem(javaLangString))
        )
    }
}