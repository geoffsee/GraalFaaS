package ltd.gsio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class HttpServerEndpointsTest {

    @Test
    fun `POST and GET functions, then invoke uploaded function`() {
        // Clean test state: remove any existing functions directory
        val faasDir = Path.of(".faas")
        deleteRecursively(faasDir)

        // Start server on an ephemeral port
        val server = createServer(0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        val boundPort = (server.address as InetSocketAddress).port
        val base = "http://localhost:$boundPort"
        try {
            // 1) Create function via HTTP
            val manifest = mapOf(
                "languageId" to "js",
                "functionName" to "handler",
                "source" to "function handler(event){ return { message: `Hello, ${'$'}{event.name}!` }; }"
            )
            val (createStatus, createBody) = httpPostJson("$base/functions", Assets.gson.toJson(manifest))
            assertEquals(201, createStatus, "POST /functions should return 201 Created, got $createStatus with body: $createBody")
            val createJson = Assets.gson.fromJson(createBody, Map::class.java)
            val assignedId = createJson["id"] as String
            // UUIDv7 pattern: version 7 and RFC variant 8,9,a,b
            val uuidV7Regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
            assertTrue(uuidV7Regex.matches(assignedId), "Server-assigned id must be a UUIDv7, got: $assignedId")
            assertEquals("js", createJson["languageId"])
            assertEquals("handler", createJson["functionName"])

            // 2) GET functions should list it
            val (listStatus, listBody) = httpGet("$base/functions")
            assertEquals(200, listStatus)
            val list = Assets.gson.fromJson(listBody, List::class.java)
            assertTrue(list.any { (it as Map<*, *>) ["id"] == assignedId }, "GET /functions should include the uploaded function. Body: $listBody")

            // 3) Invoke it
            val (invokeStatus, invokeBody) = httpPostJson("$base/invoke/$assignedId", "{" + "\"name\":\"World\"" + "}")
            assertEquals(200, invokeStatus, "POST /invoke/{id} should succeed: $invokeBody")
            val invokeJson = Assets.gson.fromJson(invokeBody, Map::class.java)
            assertEquals("Hello, World!", invokeJson["message"], "Invocation result should contain greeting. Body: $invokeBody")
        } finally {
            // Stop server and cleanup
            server.stop(0)
            deleteRecursively(faasDir)
        }
    }

    private fun httpPostJson(url: String, json: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = (stream ?: conn.inputStream).use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
        conn.disconnect()
        return status to body
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = (stream ?: conn.inputStream).use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
        conn.disconnect()
        return status to body
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { p ->
                try { Files.deleteIfExists(p) } catch (_: Exception) {}
            }
    }
}
