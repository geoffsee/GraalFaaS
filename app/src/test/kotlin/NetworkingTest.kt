package ltd.gsio.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class NetworkingTest {
    private var server: HttpServer? = null
    private var baseUrl: String = ""

    @BeforeTest
    fun startServer() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/ping", HttpHandler { exchange: HttpExchange ->
            val response = "{\"ok\":true,\"path\":\"${exchange.requestURI.path}\"}"
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        })
        srv.executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        srv.start()
        server = srv
        baseUrl = "http://127.0.0.1:${srv.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `javascript handler can fetch`() {
        val jsSource = """
            async function handler(event) {
              const r = await fetch(event.url, { headers: [['X-Test','1']] });
              const ok = r.ok;
              const status = r.status;
              const ct = r.headers.get('content-type');
              const json = JSON.stringify(await r.json());
              return { ok, status, ct, json };
            }
        """.trimIndent()
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "js",
                sourceCode = jsSource,
                enableNetwork = true,
                event = mapOf("url" to "$baseUrl/ping")
            )
        )
        assertTrue(result is Map<*, *>, "Result should be a Map, was: ${result?.javaClass}")
        val map = result as Map<*, *>
        assertEquals(true, map["ok"])
        assertEquals(200, map["status"])
        assertTrue((map["ct"] as? String)?.contains("application/json") == true)
        assertTrue((map["json"] as? String)?.contains("\"ok\":true") == true)
    }

    @Test
    fun `ruby handler can net get`() {
        val rbSource = """
            def handler(event)
              r = net.get(event['url'], { 'X-Test' => '1' })
              status = r['status']
              ct = (r['headers'] || {})['content-type']
              body = r['body']
              { 'status' => status, 'ct' => ct, 'ok' => (status >= 200 && status < 300), 'hasOk' => body.include?('"ok":true') }
            end
        """.trimIndent()
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "ruby",
                sourceCode = rbSource,
                enableNetwork = true,
                event = mapOf("url" to "$baseUrl/ping")
            )
        )
        assertTrue(result is Map<*, *>, "Result should be a Map, was: ${'$'}{result?.javaClass}")
        val map = result as Map<*, *>
        assertEquals(true, map["ok"])
        assertEquals(200, map["status"])
        assertTrue((map["ct"] as? String)?.contains("application/json") == true)
        assertEquals(true, map["hasOk"])
    }
}
