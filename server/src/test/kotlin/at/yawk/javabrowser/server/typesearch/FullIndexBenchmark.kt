package at.yawk.javabrowser.server.typesearch

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.server.Config
import at.yawk.numaec.LargeByteBuffer
import at.yawk.numaec.LargeByteBufferAllocator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList
import org.skife.jdbi.v2.Handle
import java.io.File
import java.util.concurrent.Semaphore

private const val PAGE_SIZE = 4096

fun main(args: Array<String>) {
    val config = ObjectMapper(YAMLFactory()).findAndRegisterModules().readValue(File(args[0]), Config::class.java)

    var pageCounter: PageCounter? = null

    val searchIndex = object : SearchIndex<String, String>(
            chunkSize = config.typeIndexChunkSize * 4,
            storageDir = config.typeIndexDirectory
    ) {
        override fun transformAllocator(allocator: LargeByteBufferAllocator): LargeByteBufferAllocator? {
            return LargeByteBufferAllocator { size ->
                object : RecordingBuffer(allocator.allocate(size)) {
                    override fun recordAccess(position: Long, length: Long, write: Boolean) {
                        val pc = pageCounter
                        if (pc != null) {
                            var pos = position
                            while (pos < position + length) {
                                pc.recordAccess(position / PAGE_SIZE)
                                pos += PAGE_SIZE
                            }
                        }
                    }
                }
            }
        }
    }

    println("Building index...")

    val dbi = config.database.start(mode = DbConfig.Mode.FRONTEND)
    val semaphore = Semaphore(1)
    dbi.inTransaction { outerConn: Handle, _ ->
        val artifacts = outerConn.createQuery("select id from artifacts").map { _, r, _ -> r.getString(1) }.list()
        artifacts.stream().parallel().forEach { artifactId ->
            semaphore.acquire()
            dbi.inTransaction { conn: Handle, _ ->
                println("  Building index for $artifactId")
                val itr = conn.createQuery("select binding, sourceFile from bindings where isType and artifactId = ?")
                        .bind(0, artifactId)
                        .map { _, r, _ ->
                            SearchIndex.Input(
                                    string = r.getString(1),
                                    value = r.getString(2))
                        }
                        .iterator()
                searchIndex.replace(artifactId, itr)
            }
            semaphore.release()
        }
    }

    println("Running test...")
    for (word in listOf("url", "urle", "conchashma", "conchasma")) {
        pageCounter = PageCounter()
        searchIndex.find(word).take(100).toList()
        println("  Search for query '$word' took ${pageCounter.missCount} cache misses")
    }
}

private class PageCounter(private val lruSize: Int = 1024) {
    private val queue = LongArrayList(lruSize)

    var missCount = 0
        private set

    @Synchronized
    fun recordAccess(page: Long) {
        if (!queue.remove(page)) {
            missCount++
        }
        if (queue.size() == lruSize) {
            queue.removeAtIndex(0)
        }
        queue.add(page)
    }
}

private abstract class RecordingBuffer(private val delegate: LargeByteBuffer) : LargeByteBuffer {
    protected abstract fun recordAccess(position: Long, length: Long, write: Boolean)

    override fun getInt(position: Long): Int {
        recordAccess(position, 4, write = false)
        return delegate.getInt(position)
    }

    override fun getLong(position: Long): Long {
        recordAccess(position, 8, write = false)
        return delegate.getLong(position)
    }

    override fun setShort(position: Long, value: Short) {
        recordAccess(position, 2, write = true)
        return delegate.setShort(position, value)
    }

    override fun setLong(position: Long, value: Long) {
        recordAccess(position, 8, write = true)
        return delegate.setLong(position, value)
    }

    override fun getShort(position: Long): Short {
        recordAccess(position, 2, write = false)
        return delegate.getShort(position)
    }

    override fun setInt(position: Long, value: Int) {
        recordAccess(position, 4, write = true)
        return delegate.setInt(position, value)
    }

    override fun getByte(position: Long): Byte {
        recordAccess(position, 1, write = false)
        return delegate.getByte(position)
    }

    override fun size(): Long {
        return delegate.size()
    }

    override fun copyFrom(from: LargeByteBuffer, fromIndex: Long, toIndex: Long, length: Long) {
        this.recordAccess(toIndex, length, write = true)
        if (from is RecordingBuffer) {
            from.recordAccess(fromIndex, length, write = false)
            this.delegate.copyFrom(from.delegate, fromIndex, toIndex, length)
        } else {
            this.delegate.copyFrom(from, fromIndex, toIndex, length)
        }
    }

    override fun setByte(position: Long, value: Byte) {
        recordAccess(position, 1, write = true)
        return delegate.setByte(position, value)
    }
}