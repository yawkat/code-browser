package at.yawk.javabrowser

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.collections.api.collection.primitive.MutableLongCollection
import org.eclipse.collections.api.map.primitive.MutableObjectLongMap
import org.eclipse.collections.api.tuple.primitive.ObjectLongPair
import org.eclipse.collections.impl.factory.primitive.ObjectLongMaps
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import java.util.TreeSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

private val POISON_PILL = ByteArray(0)

private const val OUTPUT_COUNT = 1000
private const val FETCH_SIZE = 1024

private const val PRUNE_HIGH_WATERMARK = 10_000_000
private const val PRUNE_LOW_WATERMARK = 8_000_000

private const val PROGRESS_WIDTH = 40

fun main(args: Array<String>) {
    val dbConfig = DbConfig(args[0], args[1], args[2])
    val dbi = dbConfig.start(DbConfig.Mode.FRONTEND)

    val frequencyMap = ObjectLongMaps.mutable.empty<String>()

    dbi.inTransaction<Unit, Exception> { conn ->
        conn.transactionIsolationLevel = TransactionIsolationLevel.READ_UNCOMMITTED

        TableVisitor("source_file", "annotations", frequencyMap).run(conn)
        TableVisitor("binding", "description", frequencyMap).run(conn)
    }

    println("Collecting top entries…")

    val top = TreeSet<ObjectLongPair<String>>(Comparator.comparingLong { it.two })
    for (pair in frequencyMap.keyValuesView()) {
        top.add(pair)
        while (top.size > OUTPUT_COUNT) {
            val iterator = top.iterator()
            iterator.next()
            iterator.remove()
        }
    }
    for (pair in top) {
        println("${pair.two}\t${pair.one}")
    }
}


private class TableVisitor(private val table: String,
                           private val column: String,
                           private val frequencyMap: MutableObjectLongMap<String>) {
    val rowCount = AtomicLong()
    val queue = ArrayBlockingQueue<ByteArray>(FETCH_SIZE * 4)

    fun run(conn: Handle) {
        val parserThread = Thread(::parse, "Parser thread")
        parserThread.start()

        println("Loading input size for $table")
        rowCount.set((conn.select("select count(*) from $table").mapToMap().single().values.single() as Number).toLong())
        println("Working $rowCount rows")
        for (bytes in conn.createQuery("select $column from $table")
                .setFetchSize(FETCH_SIZE)
                .map { r, _, _ -> r.getBytes(1) }) {
            queue.put(bytes)
        }
        queue.put(POISON_PILL)

        parserThread.join()
    }

    private fun parse() {
        val mapper = ObjectMapper()

        var i = 0L
        while (true) {
            if (i != 0L && i % 43 == 0L) progress(i, table)

            val array = queue.take()
            @Suppress("ReplaceArrayEqualityOpWithArraysEquals")
            if (array == POISON_PILL) break
            val parser = mapper.createParser(array)
            while (true) {
                val token = parser.nextToken() ?: break
                if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING) {
                    frequencyMap.addToValue(parser.text, 1)
                    if (frequencyMap.size() > PRUNE_HIGH_WATERMARK) {
                        pruneMin(frequencyMap.values(), PRUNE_LOW_WATERMARK)
                    }
                }
            }

            i++
        }
        print('\n')
    }

    private fun progress(current: Long, tag: String) {
        val limit = rowCount.get()
        val backlog = queue.size

        val builder = StringBuilder("[")
        val progress = (PROGRESS_WIDTH.toDouble() * current / limit).roundToLong()
        for (i in 0 until PROGRESS_WIDTH) {
            builder.append(if (i <= progress) '=' else ' ')
        }
        val limitString = limit.toString()
        val currentString = current.toString().padStart(limitString.length)
        builder.append("] ").append(currentString).append(" / ").append(limitString).append(' ')
                .append(tag)
                .append(" backlog=").append(backlog)
                .append('\r')
        print(builder)
    }
}

private fun pruneMin(itr: MutableLongCollection, size: Int) {
    val sorted = itr.toSortedArray()
    var toRemove = sorted.size - size
    if (toRemove > 0) {
        val lowBar = sorted[toRemove - 1]
        val iterator = itr.longIterator()
        while (toRemove > 0 && iterator.hasNext()) {
            if (iterator.next() <= lowBar) {
                iterator.remove()
                toRemove--
            }
        }
    }
}