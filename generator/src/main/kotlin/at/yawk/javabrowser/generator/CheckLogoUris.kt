package at.yawk.javabrowser.generator

import java.net.URL
import java.nio.file.Paths

fun main(args: Array<String>) {
    val cfg = Config.fromFile(Paths.get(args[0]))
    val logoUrls = cfg.artifacts.mapNotNull { it.metadata?.logoUrl }.toSet()
    for (logoUrl in logoUrls) {
        println("Checking $logoUrl")
        URL(logoUrl).openStream().use { it.readBytes() }
    }
}