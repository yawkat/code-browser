package at.yawk.javabrowser.generator

import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class MavenTest {
    @Test
    fun deps() {
        val dependencies = MavenDependencyResolver().getMavenDependencies("com.google.guava", "guava", "25.1-jre")
        Assert.assertTrue(
                dependencies.map { it.coordinate.toCanonicalForm() }
                        .any { it.matches("com.google.errorprone:.*".toRegex()) }
        )
    }

    @Test
    fun `central location`() {
        MavenDependencyResolver().getMavenDependencies("org.jdbi", "jdbi3-testing", "3.8.2")
    }
}