package io.github.surioustype.localscribe.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

fun interface NetworkClient {
    suspend fun get(url: String, headers: Map<String, String>): NetworkResponse
}

interface NetworkResponse : Closeable {
    val statusCode: Int
    val headers: Map<String, String>
    val body: InputStream
}

class ByteArrayNetworkResponse(
    override val statusCode: Int,
    override val headers: Map<String, String> = emptyMap(),
    bytes: ByteArray = byteArrayOf(),
) : NetworkResponse {
    override val body: InputStream = ByteArrayInputStream(bytes)

    override fun close() = body.close()
}

class HttpsNetworkClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : NetworkClient {
    override suspend fun get(url: String, headers: Map<String, String>): NetworkResponse =
        withContext(Dispatchers.IO) { open(url, headers, redirectsRemaining = 5) }

    private fun open(
        url: String,
        headers: Map<String, String>,
        redirectsRemaining: Int,
    ): NetworkResponse {
        val uri = requireHttps(url)
        val connection =
            (uri.toURL().openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach(::setRequestProperty)
            }
        val status = connection.responseCode
        if (status in REDIRECT_CODES) {
            val location =
                connection.getHeaderField("Location")
                    ?: throw IllegalStateException("HTTPS redirect omitted Location")
            connection.disconnect()
            require(redirectsRemaining > 0) { "Too many HTTPS redirects" }
            return open(uri.resolve(location).toString(), headers, redirectsRemaining - 1)
        }
        val responseHeaders =
            buildMap {
                connection.headerFields.forEach { (name, values) ->
                    if (name != null && !values.isNullOrEmpty()) put(name, values.joinToString(","))
                }
            }
        val stream =
            if (status >=
                400
            ) {
                connection.errorStream ?: ByteArrayInputStream(byteArrayOf())
            } else {
                connection.inputStream
            }
        return ConnectionResponse(connection, status, responseHeaders, stream)
    }

    private fun requireHttps(value: String): URI {
        val uri = URI(value)
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host != null &&
                uri.userInfo == null,
        ) {
            "Only public HTTPS URLs are permitted"
        }
        return uri
    }

    private class ConnectionResponse(
        private val connection: HttpsURLConnection,
        override val statusCode: Int,
        override val headers: Map<String, String>,
        override val body: InputStream,
    ) : NetworkResponse {
        override fun close() {
            body.close()
            connection.disconnect()
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
