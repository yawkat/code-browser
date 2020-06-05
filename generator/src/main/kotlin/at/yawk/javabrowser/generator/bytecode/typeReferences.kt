package at.yawk.javabrowser.generator.bytecode

import org.objectweb.asm.TypePath
import org.objectweb.asm.TypeReference

internal fun sortToString(typeReference: TypeReference) = when (typeReference.sort) {
    TypeReference.CLASS_TYPE_PARAMETER -> "CLASS_TYPE_PARAMETER"
    TypeReference.METHOD_TYPE_PARAMETER -> "METHOD_TYPE_PARAMETER"
    TypeReference.CLASS_EXTENDS -> "CLASS_EXTENDS"
    TypeReference.CLASS_TYPE_PARAMETER_BOUND -> "CLASS_TYPE_PARAMETER_BOUND"
    TypeReference.METHOD_TYPE_PARAMETER_BOUND -> "METHOD_TYPE_PARAMETER_BOUND"
    TypeReference.FIELD -> "FIELD"
    TypeReference.METHOD_RETURN -> "METHOD_RETURN"
    TypeReference.METHOD_RECEIVER -> "METHOD_RECEIVER"
    TypeReference.METHOD_FORMAL_PARAMETER -> "METHOD_FORMAL_PARAMETER"
    TypeReference.THROWS -> "THROWS"
    TypeReference.LOCAL_VARIABLE -> "LOCAL_VARIABLE"
    TypeReference.RESOURCE_VARIABLE -> "RESOURCE_VARIABLE"
    TypeReference.EXCEPTION_PARAMETER -> "EXCEPTION_PARAMETER"
    TypeReference.INSTANCEOF -> "INSTANCEOF"
    TypeReference.NEW -> "NEW"
    TypeReference.CONSTRUCTOR_REFERENCE -> "CONSTRUCTOR_REFERENCE"
    TypeReference.METHOD_REFERENCE -> "METHOD_REFERENCE"
    TypeReference.CAST -> "CAST"
    TypeReference.CONSTRUCTOR_INVOCATION_TYPE_ARGUMENT -> "CONSTRUCTOR_INVOCATION_TYPE_ARGUMENT"
    TypeReference.METHOD_INVOCATION_TYPE_ARGUMENT -> "METHOD_INVOCATION_TYPE_ARGUMENT"
    TypeReference.CONSTRUCTOR_REFERENCE_TYPE_ARGUMENT -> "CONSTRUCTOR_REFERENCE_TYPE_ARGUMENT"
    TypeReference.METHOD_REFERENCE_TYPE_ARGUMENT -> "METHOD_REFERENCE_TYPE_ARGUMENT"
    else -> throw AssertionError("unknown sort: ${typeReference.sort}")
}

internal fun typePathStepToString(step: Int) = when (step) {
    TypePath.ARRAY_ELEMENT -> "ARRAY_ELEMENT"
    TypePath.INNER_TYPE -> "INNER_TYPE"
    TypePath.WILDCARD_BOUND -> "WILDCARD_BOUND"
    TypePath.TYPE_ARGUMENT -> "TYPE_ARGUMENT"
    else -> throw AssertionError("unknown type path step: $step")
}