package at.yawk.javabrowser.server

/**
 * @author yawkat
 */
class HttpException(
        val status: Int,
        message: String
) : Exception(message)