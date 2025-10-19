package ltd.gsio.app

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A minimal host-side HTTP proxy exposed to guest languages as a "virtualized" networking API.
 * It supports simple JSON/text-friendly requests without exposing full host capabilities.
 */
class VirtualNet(
    private val connectTimeoutMillis: Long = 10_000,
    private val requestTimeoutMillis: Long = 20_000,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Executes an HTTP request and returns a simple map: { status, headers, body }.
     * Body is returned as UTF-8 string. Headers are a map name -> firstValue.
     */
    fun http(method: String, url: String, body: String?, headers: Map<String, String>?): Map<String, Any?> {
        val m = method.uppercase()
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(requestTimeoutMillis))

        // sanitize headers: avoid restricted ones being set by guests
        val safeHeaders = linkedMapOf<String, String>()
        headers?.forEach { (k, v) ->
            if (!isRestrictedHeader(k)) safeHeaders[k] = v
        }
        for ((k, v) in safeHeaders) builder.header(k, v)

        val publisher = if (m == "GET" || m == "HEAD") {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body ?: "")
        }
        val request = builder.method(m, publisher).build()

        val resp: HttpResponse<String> = client.send(request, HttpResponse.BodyHandlers.ofString())
        val headersMap = LinkedHashMap<String, String>()
        resp.headers().map().forEach { (k, values) ->
            if (values.isNotEmpty()) headersMap[k] = values[0]
        }
        return linkedMapOf(
            "status" to resp.statusCode(),
            "headers" to headersMap,
            "body" to resp.body(),
        )
    }

    private fun isRestrictedHeader(name: String): Boolean {
        val n = name.lowercase()
        return n in setOf(
            "host", "content-length", "connection", "transfer-encoding"
        )
    }
}
