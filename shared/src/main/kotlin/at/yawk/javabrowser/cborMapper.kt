package at.yawk.javabrowser

import com.fasterxml.jackson.core.Base64Variant
import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.ObjectCodec
import com.fasterxml.jackson.core.base.GeneratorBase
import com.fasterxml.jackson.core.io.IOContext
import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator
import com.fasterxml.jackson.dataformat.cbor.CBORParser
import org.eclipse.collections.api.map.primitive.IntObjectMap
import org.eclipse.collections.api.map.primitive.ObjectIntMap
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.math.BigInteger

private const val ABSENT_VALUE = Int.MIN_VALUE
private const val INTERNED_TAG = 6

internal object CommonStrings {
    // encoded as one byte
    internal val COMMON_STRINGS_1 = listOf(
            // empty string is already single byte, don't include it.
            "binding-ref",
            "lv-ref",
            "binding",
            "styleClass",
            "style",
            "id",
            "start",
            "length",
            "annotation",
            "type"
    )

    // encoded as two bytes
    internal val COMMON_STRINGS_2 = listOf(
            "long",
            "type-variable",
            "boolean",
            "Object",
            "String",
            "javadoc",
            "int",
            "void",
            "typeBinding",
            "static",
            "javadoc-tag",
            "string-literal",
            "parameterTypeBindings",
            "method",
            "final",
            "binding-decl",
            "modifiers",
            "description",
            "parent",
            "declaration",
            "name",
            "field",
            "effectively-final",
            "simpleName",
            "typeParameters",
            "kind",
            "line-ref",
            "sourceFile",
            "line",
            "variable",
            "hash",
            "keyword",
            "number-literal",
            "comment"
    )

    internal val stringToIndex: ObjectIntMap<String>
    internal val indexToString: IntObjectMap<String>

    init {
        stringToIndex = ObjectIntMaps.mutable.empty<String>().also {
            for ((i, s) in COMMON_STRINGS_1.withIndex()) {
                require(s.length > 1)
                it.put(s, -24 + i)
            }
            for ((i, s) in COMMON_STRINGS_2.withIndex()) {
                require(s.length > 2)
                it.put(s, 24 + i)
            }
        }
        indexToString = stringToIndex.flipUniqueValues()
        require(stringToIndex.size() == indexToString.size())
        require(!indexToString.containsKey(ABSENT_VALUE))
    }
}

private class CompressedGenerator(private val delegate: CBORGenerator)
    : GeneratorBase(delegate.featureMask, delegate.codec, null) {

    override fun writeRawUTF8String(text: ByteArray?, offset: Int, length: Int) =
            delegate.writeRawUTF8String(text, offset, length)

    override fun writeNull() = delegate.writeNull()

    override fun _verifyValueWrite(typeMsg: String?) {
    }

    override fun writeStartArray() = delegate.writeStartArray()
    override fun writeEndArray() = delegate.writeEndArray()

    override fun writeNumber(v: Int) = delegate.writeNumber(v)
    override fun writeNumber(v: Long) = delegate.writeNumber(v)
    override fun writeNumber(v: BigInteger) = delegate.writeNumber(v)
    override fun writeNumber(v: Double) = delegate.writeNumber(v)
    override fun writeNumber(v: Float) = delegate.writeNumber(v)
    override fun writeNumber(v: BigDecimal) = delegate.writeNumber(v)
    override fun writeNumber(encodedValue: String) = delegate.writeNumber(encodedValue)

    override fun writeStartObject() = delegate.writeStartObject()
    override fun writeEndObject() = delegate.writeEndObject()

    override fun writeFieldName(name: String) {
        val compressedId = CommonStrings.stringToIndex.getIfAbsent(name, ABSENT_VALUE)
        if (compressedId == ABSENT_VALUE) {
            delegate.writeFieldName(name)
        } else {
            delegate.writeFieldId(compressedId.toLong())
        }
    }
    override fun writeString(text: String) {
        val compressedId = CommonStrings.stringToIndex.getIfAbsent(text, ABSENT_VALUE)
        if (compressedId == ABSENT_VALUE) {
            delegate.writeString(text)
        } else {
            delegate.writeTag(INTERNED_TAG)
            delegate.writeNumber(compressedId)
        }
    }

    override fun writeString(text: CharArray, offset: Int, len: Int) = delegate.writeString(text, offset, len)

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    override fun writeBoolean(state: Boolean) = delegate.writeBoolean(state)

    override fun writeBinary(bv: Base64Variant, data: ByteArray, offset: Int, len: Int) =
            delegate.writeBinary(bv, data, offset, len)

    override fun _releaseBuffers() {
    }

    override fun writeRaw(text: String) = _reportUnsupportedOperation()
    override fun writeRaw(text: String, offset: Int, len: Int) = _reportUnsupportedOperation()
    override fun writeRaw(text: CharArray, offset: Int, len: Int) = _reportUnsupportedOperation()
    override fun writeRaw(c: Char) = _reportUnsupportedOperation()

    override fun writeUTF8String(text: ByteArray, offset: Int, length: Int) =
            writeRawUTF8String(text, offset, length)
}

private class CompressedParser(ctxt: IOContext?,
                       parserFeatures: Int,
                       cborFeatures: Int,
                       codec: ObjectCodec?,
                       sym: ByteQuadsCanonicalizer?,
                       `in`: InputStream?,
                       inputBuffer: ByteArray?,
                       start: Int,
                       end: Int,
                       bufferRecyclable: Boolean) :
        CBORParser(ctxt, parserFeatures, cborFeatures, codec, sym, `in`, inputBuffer, start, end, bufferRecyclable) {
    override fun getText(): String {
        if (currentTag == INTERNED_TAG) {
            return CommonStrings.indexToString[intValue]
        }
        return super.getText()
    }

    override fun _numberToName(ch: Int, neg: Boolean): String {
        val lowBits = ch and 0x1F
        var i: Int
        if (lowBits <= 23) {
            i = lowBits
        } else {
            i = when (lowBits) {
                24 -> decode8Bits()
                else -> throw UnsupportedOperationException()
            }
        }
        if (neg) {
            i = -i - 1
        }
        return CommonStrings.indexToString[i]
    }

    private fun decode8Bits(): Int {
        if (_inputPtr >= _inputEnd) {
            loadMoreGuaranteed()
        }
        return _inputBuffer[_inputPtr++].toInt() and 0xFF
    }
}

class CompressedFactory : JsonFactory() {
    private val delegate = FactoryDelegate()

    override fun _createUTF8Generator(out: OutputStream, ctxt: IOContext?): JsonGenerator {
        return CompressedGenerator(delegate.expose_createUTF8Generator(out, ctxt))
    }

    override fun createGenerator(out: OutputStream?): JsonGenerator {
        return CompressedGenerator(delegate.createGenerator(out))
    }

    override fun createGenerator(out: OutputStream?, enc: JsonEncoding?): JsonGenerator {
        return CompressedGenerator(delegate.createGenerator(out, enc))
    }

    override fun _createParser(data: ByteArray, offset: Int, len: Int, ctxt: IOContext): JsonParser {
        return CompressedParser(
                ctxt = ctxt,
                parserFeatures = delegate.parserFeatures,
                cborFeatures = delegate.get_factoryFeatures(),
                codec = delegate.codec,
                sym = delegate.get_byteSymbolCanonicalizer().makeChild(delegate.get_factoryFeatures()),
                `in` = null,
                inputBuffer = data,
                start = offset,
                end = offset + len,
                bufferRecyclable = false
        )
    }

    override fun _createParser(`in`: InputStream, ctxt: IOContext): JsonParser {
        return CompressedParser(
                ctxt = ctxt,
                parserFeatures = delegate.parserFeatures,
                cborFeatures = delegate.get_factoryFeatures(),
                codec = delegate.codec,
                sym = delegate.get_byteSymbolCanonicalizer().makeChild(delegate.get_factoryFeatures()),
                `in` = `in`,
                inputBuffer = ctxt.allocReadIOBuffer(),
                start = 0,
                end = 0,
                bufferRecyclable = true
        )
    }

    @Suppress("FunctionName")
    private class FactoryDelegate : CBORFactory() {
        fun expose_createUTF8Generator(out: OutputStream?, ctxt: IOContext?): CBORGenerator {
            return super._createUTF8Generator(out, ctxt)
        }

        fun get_factoryFeatures() = _factoryFeatures
        fun get_byteSymbolCanonicalizer() = _byteSymbolCanonicalizer!!
    }
}
