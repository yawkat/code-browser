package at.yawk.javabrowser.server

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.hash.Hashing
import com.google.common.io.ByteStreams
import com.google.common.net.MediaType
import freemarker.core.Environment
import freemarker.template.SimpleNumber
import freemarker.template.SimpleScalar
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateException
import freemarker.template.TemplateModel
import io.undertow.server.HttpHandler
import io.undertow.util.Headers
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.inject.Singleton
import javax.xml.bind.DatatypeConverter
import kotlin.math.roundToInt

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(ImageCache::class.java)

@Singleton
class ImageCache {
    companion object {
        const val PATTERN = "/img/{id}"
    }

    private val byId = ConcurrentHashMap<String, Entry>()
    private val cache = CacheBuilder.newBuilder()
            .build(object : CacheLoader<CacheKey, Entry>() {
                override fun load(key: CacheKey): Entry {
                    val entry = parseImage(key)
                    byId[entry.id] = entry
                    return entry
                }
            })

    val handler = HttpHandler { exchange ->
        val id = exchange.queryParameters["id"]?.peekFirst()
                ?: throw HttpException(404, "No image ID given")

        val entry = byId[id] ?: throw HttpException(404, "Image not found")
        exchange.responseHeaders.put(Headers.CACHE_CONTROL, "max-age=" + TimeUnit.DAYS.toSeconds(30))
        exchange.responseHeaders.put(Headers.ETAG, entry.id)

        val expectedTag: String? = exchange.requestHeaders.getFirst(Headers.IF_NONE_MATCH)
        if (expectedTag == id) {
            exchange.statusCode = 304
        } else {
            exchange.responseHeaders.put(Headers.CONTENT_TYPE, entry.mediaType.toString())
            exchange.outputStream.write(entry.bytes)
        }
    }

    val directive = TemplateDirectiveModel { env: Environment,
                                             params: Map<*, *>,
                                             _: Array<TemplateModel>,
                                             body: TemplateDirectiveBody? ->

        if (body != null) throw TemplateException("Should not have a body", env)
        val url: String = (((params["url"] ?: throw TemplateException("url= required", env)) as? SimpleScalar)
                ?: throw TemplateException("url must be string", env)).asString
        val maxWidth: Int = (((params["maxWidth"] ?: SimpleNumber(Int.MAX_VALUE)) as? SimpleNumber)
                ?: throw TemplateException("maxWidth must be number", env)).asNumber.toInt()
        val maxHeight: Int = (((params["maxHeight"] ?: SimpleNumber(Int.MAX_VALUE)) as? SimpleNumber)
                ?: throw TemplateException("maxHeight must be number", env)).asNumber.toInt()
        env.out.write(load(CacheKey(url, maxWidth, maxHeight))?.toASCIIString() ?: "")
    }

    private fun parseImage(key: CacheKey): Entry {
        val connection = URL(key.url).openConnection()
        connection.setRequestProperty("User-Agent", "yawkat/java-browser image cache fetcher")
        val stream = BufferedInputStream(connection.getInputStream())
        stream.mark(8)

        when (stream.read()) {
            // jpg, png, gif
            0xff, 0x89, 0x47 -> {
                stream.reset()

                val image = ImageIO.read(stream)
                val scaled: BufferedImage
                if (key.maxHeight < image.height || key.maxWidth < image.width) {
                    val factor = Math.min(
                            key.maxHeight.toDouble() / image.height,
                            key.maxWidth.toDouble() / image.width
                    )
                    scaled = BufferedImage(
                            (image.width * factor).roundToInt(),
                            (image.height * factor).roundToInt(),
                            BufferedImage.TYPE_INT_ARGB)
                    val gfx = scaled.createGraphics()
                    gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    gfx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    gfx.drawRenderedImage(image, AffineTransform.getScaleInstance(factor, factor))
                } else {
                    scaled = image
                }
                val baos = ByteArrayOutputStream()
                ImageIO.write(scaled, "PNG", baos)
                return Entry(baos.toByteArray(), MediaType.PNG)
            }
        }

        // svg
        stream.reset()
        return Entry(ByteStreams.toByteArray(stream), MediaType.SVG_UTF_8)
    }

    private fun load(key: CacheKey): URI? {
        try {
            val entry = cache[key]
            return URI("/img/${entry.id}")
        } catch (e: Exception) {
            log.error("Failed to load image ${key.url} to cache", e)
            return null
        }
    }

    data class CacheKey(
            val url: String,
            val maxWidth: Int = Int.MAX_VALUE,
            val maxHeight: Int = Int.MAX_VALUE
    )

    private class Entry(val bytes: ByteArray, val mediaType: MediaType) {
        val id: String = DatatypeConverter.printHexBinary(Hashing.sha256().hashBytes(bytes).asBytes())
    }
}