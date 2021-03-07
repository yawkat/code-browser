package at.yawk.javabrowser.generator

import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.regex.Pattern

private val CONTENT_RANGE_PATTERN = Pattern.compile("bytes (?<start>\\d+)-(?<end>\\d+)/(?<size>\\d+)")

/**
 * Wrapper around [URL#openConnection] that reopens closed connections with appropriate `Range` headers to continue a
 * download in progress.
 */
class ResumingUrlInputStream(private val url: URL) : InputStream() {
    private var delegate: InputStream? = null
    private var position: Long = 0
    private var fullSize: Long = -1

    private val eof: Boolean
        get() = fullSize in 0..position

    private fun reopen() {
        if (eof) {
            throw IllegalStateException("Already hit EOF")
        }
        val connection = url.openConnection()
        if (position == 0L) {
            delegate = connection.getInputStream()
            fullSize = connection.getHeaderFieldLong("content-length", -1)
        } else {
            connection.setRequestProperty("Range", "bytes=$position-")
            delegate = connection.getInputStream()
        }
        val contentRange = connection.getHeaderField("Content-Range")
        if (contentRange != null) {
            val matcher = CONTENT_RANGE_PATTERN.matcher(contentRange)
            if (!matcher.matches()) {
                throw IOException("Unsupported Content-Range in response: $contentRange")
            }
            val start = matcher.group("start").toLong()
            val size = matcher.group("size").toLong()
            if (start < this.position) {
                delegate!!.skip(this.position - start)
            } else if (start > this.position) {
                throw IOException("response Content-Range start after requested position")
            }
            this.fullSize = size
        }
        if (fullSize < 0L) {
            throw IOException("Unknown file size")
        }
    }

    override fun read(): Int {
        while (true) {
            if (delegate == null) {
                if (eof) {
                    return -1
                }
                reopen()
            }
            val value = delegate!!.read()
            if (value == -1) {
                delegate = null
            } else {
                position++
                return value
            }
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            if (delegate == null) {
                if (eof) {
                    return -1
                }
                reopen()
            }
            val n = delegate!!.read(b, off, len)
            if (n == -1) {
                delegate = null
            } else {
                position += n
                return n
            }
        }
    }
}