package at.yawk.javabrowser.server.artifact

import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class ArtifactNodeTest {
    @Test
    fun test() {
        val root = ArtifactNode.build(listOf(ArtifactNode.Prototype(0, "java/8")))
        Assert.assertEquals(root.children.size, 1)
        Assert.assertEquals(root.children["java"]?.children?.size, 1)
        Assert.assertEquals(root.children["java"]?.stringId, "java")
        Assert.assertEquals(root.children["java"]?.idInParent, "java")
        Assert.assertEquals(root.children["java"]?.children?.get("8")?.children?.size, 0)
        Assert.assertEquals(root.children["java"]?.children?.get("8")?.stringId, "java/8")
        Assert.assertEquals(root.children["java"]?.children?.get("8")?.idInParent, "8")
    }
}