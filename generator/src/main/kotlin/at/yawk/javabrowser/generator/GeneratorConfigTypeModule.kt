package at.yawk.javabrowser.generator

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.deser.Deserializers
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates

/**
 * @author yawkat
 */
object GeneratorConfigTypeModule : Module() {
    override fun getModuleName() = "GeneratorConfigTypeModule"

    override fun version() = Version.unknownVersion()!!

    override fun setupModule(context: SetupContext) {
        context.addDeserializers(object : Deserializers.Base() {
            override fun findBeanDeserializer(type: JavaType,
                                              config: DeserializationConfig,
                                              beanDesc: BeanDescription): JsonDeserializer<*>? {
                if (type.rawClass == MavenCoordinate::class.java) {
                    return MavenCoordinateDeserializer
                }
                return super.findBeanDeserializer(type, config, beanDesc)
            }
        })
    }

    private object MavenCoordinateDeserializer : JsonDeserializer<MavenCoordinate>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): MavenCoordinate {
            return MavenCoordinates.createCoordinate(p.text)
        }
    }
}