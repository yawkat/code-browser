package at.yawk.javabrowser.server

import at.yawk.javabrowser.CompressedFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.SingletonSupport

internal val cborMapper = ObjectMapper(CompressedFactory()).registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))