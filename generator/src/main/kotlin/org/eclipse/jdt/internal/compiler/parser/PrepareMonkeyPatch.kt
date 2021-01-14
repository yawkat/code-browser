package org.eclipse.jdt.internal.compiler.parser

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * This object disables class file signature verification so that the monkey patch in AbstractCommentParser works. This
 * class must be loaded before AbstractCommentParser.
 */
object PrepareMonkeyPatch {
    init {
        // run once

        val field = ClassLoader::class.java.getDeclaredField("package2certs")
        val modifiersField = Field::class.java.getDeclaredField("modifiers")
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
        field.isAccessible = true
        field.set(javaClass.classLoader, object : ConcurrentHashMap<Any, Any>() {
            override fun putIfAbsent(key: Any, value: Any): Any? {
                return null
            }

            override fun put(key: Any, value: Any): Any? {
                return null
            }
        })
    }
}