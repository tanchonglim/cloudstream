package com.lagradost.cloudstream3.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * A deliberately tiny HTTP/1.1 implementation used by [RemoteControlServer].
 *
 * It only supports what the companion remote app needs: a request line, headers,
 * an optional Content-Length body and a single response per connection.
 * Keeping it dependency free avoids pulling a full web server into the app.
 */
internal object MiniHttp {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 1024 * 1024

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
        val remoteAddress: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(
        val status: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8",
    )

    class HttpException(val status: Int, message: String) : Exception(message)

    private fun readLine(input: InputStream, budget: IntArray): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            if (--budget[0] < 0) throw HttpException(431, "Headers too large")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Throwable) {
        value
    }

    fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').filter { it.isNotEmpty() }.associate { part ->
            val idx = part.indexOf('=')
            if (idx < 0) decode(part) to "" else decode(part.substring(0, idx)) to decode(part.substring(idx + 1))
        }
    }

    fun readRequest(rawInput: InputStream, remoteAddress: String): Request? {
        val input = BufferedInputStream(rawInput)
        val budget = intArrayOf(MAX_HEADER_BYTES)
        val requestLine = readLine(input, budget)?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw HttpException(400, "Malformed request line")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input, budget) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) throw HttpException(413, "Body too large")
        val bodyBytes = ByteArray(length.coerceAtLeast(0))
        var read = 0
        while (read < length) {
            val r = input.read(bodyBytes, read, length - read)
            if (r == -1) break
            read += r
        }

        val queryIdx = target.indexOf('?')
        val path = if (queryIdx < 0) target else target.substring(0, queryIdx)
        val query = if (queryIdx < 0) emptyMap() else parseQuery(target.substring(queryIdx + 1))

        return Request(
            method = method,
            path = decode(path),
            query = query,
            headers = headers,
            body = String(bodyBytes, 0, read, Charsets.UTF_8),
            remoteAddress = remoteAddress,
        )
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> if (status >= 500) "Internal Server Error" else "Unknown"
    }

    fun writeResponse(output: OutputStream, response: Response) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }
}
