package at.yawk.javabrowser.server

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URLEncoder

/**
 * @author yawkat
 */
@JsonSerialize(using = TypeListResponse.Serializer::class)
class TypeListResponse(
        private val dbi: DBI,
        private val artifactId: String
) {
    class Serializer : JsonSerializer<TypeListResponse>() {
        override fun serialize(value: TypeListResponse, gen: JsonGenerator, serializers: SerializerProvider) {
            value.dbi.inTransaction { conn: Handle, _ ->
                gen.writeStartObject()
                conn.createQuery("select binding, sourceFile from bindings where artifactId = ? and isType")
                        .bind(0, value.artifactId)
                        .map({ _, r, _ -> r.getString("binding") to r.getString("sourceFile") })
                        .iterator().forEach { (binding, sourceFile) ->
                            if (binding.isNotEmpty()) {
                                gen.writeFieldName(binding)
                                gen.writeString(sourceFile + "#" + URLEncoder.encode(binding, "UTF-8"))
                            }
                        }
                gen.writeEndObject()
            }
        }


    }
}
