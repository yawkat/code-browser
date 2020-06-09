package org.objectweb.asm

import java.nio.ByteBuffer

val Attribute.content: ByteBuffer
    get() {
        val vector = write(null, null, 0, 0, 0)
        return ByteBuffer.wrap(vector.data, 0, vector.length)
    }

fun ClassReader.readUtf(i: Int, buffer: CharArray): String = this.readUtf(i, buffer)