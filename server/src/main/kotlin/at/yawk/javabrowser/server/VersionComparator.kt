package at.yawk.javabrowser.server

/**
 * Compares dot/slash-separated strings numerically and alphabetically, placing "later" numerical values first.
 *
 * 'java/8' > 'java/11'
 */
object VersionComparator : Comparator<String> {
    override fun compare(o1: String, o2: String): Int {
        val p1 = o1.split('.', '/')
        val p2 = o2.split('.', '/')
        var i = 0
        while (true) {
            if (i >= p1.size) {
                if (i >= p2.size) return 0
                return -1
            }
            if (i >= p2.size) {
                return 1
            }

            val e1 = p1[i]
            val e2 = p2[i]
            if (e1 != e2) {
                try {
                    val k1 = e1.toLong()
                    val k2 = e2.toLong()
                    // newer first
                    return -java.lang.Long.compare(k1, k2)
                } catch (e: NumberFormatException) {
                    return e1.compareTo(e2)
                }
            }

            i++
        }
    }
}