package at.yawk.javabrowser.server

import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers

/**
 * @author yawkat
 */
internal fun HttpServerExchange.isCrawler(): Boolean {
    val agents = requestHeaders[Headers.USER_AGENT]
    if (agents.isEmpty()) {
        return false
    }
    val agent = agents.first
    // from https://www.keycdn.com/blog/web-crawlers
    return agent.contains("Googlebot") ||
            agent.contains("Bingbot") ||
            agent.contains("Slurp") ||
            agent.contains("DuckDuckBot") ||
            agent.contains("Baiduspider") ||
            agent.contains("YandexBot") ||
            agent.contains("Sogou") ||
            agent.contains("Exabot") ||
            agent.contains("facebot") ||
            agent.contains("facebookexternalhit") ||
            agent.contains("ia_archiver")
}