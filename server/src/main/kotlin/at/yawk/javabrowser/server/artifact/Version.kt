package at.yawk.javabrowser.server.artifact

/**
 * @author yawkat
 */
class Version(name: String) : Comparable<Version> {
    private val parts = name.split('.')
    private val partNumbers = parts.map {
        try {
            it.toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    override fun compareTo(other: Version): Int {
        var i = 0
        while (true) {
            if (i >= parts.size) {
                if (i >= other.parts.size) {
                    return 0
                }
                return -1
            }
            if (i >= other.parts.size) {
                return 1
            }

            if (parts[i] != other.parts[i]) {
                val k1 = partNumbers[i]
                val k2 = other.partNumbers[i]
                return if (k1 != null && k2 != null) {
                    java.lang.Long.compare(k1, k2)
                } else {
                    parts[i].compareTo(other.parts[i])
                }
            }
            i++
        }
    }

    fun matchingPrefixLength(other: Version): Int {
        var i = 0
        while (i < parts.size && i < other.parts.size &&
                parts[i] == other.parts[i]) {
            i++
        }
        return i
    }
}