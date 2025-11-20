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

class ResourcesIntegrationTest {

    @Test
    fun `create kv resource with ownership and use it via event_platform in JS`() {
        // Clean test state
        val faasDir = Path.of(".faas")
        deleteRecursively(faasDir)

        val server = createServer(0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        val boundPort = (server.address as InetSocketAddress).port
        val base = "http://localhost:$boundPort"
        try {
            // Create a JS function that uses event.platform.kv
            val manifest = mapOf(
                "languageId" to "js",
                "functionName" to "handler",
                "source" to (
                    """
                    function handler(event){
                      if (!event.platform || !event.platform.kv) {
                        return { error: 'platform.kv not available' };
                      }
                      const kv = event.platform.kv;
                      kv.put('foo','bar');
                      return { foo: String(kv.get('foo')) };
                    }
                    """.trimIndent()
                )
            )
            val (createFnStatus, createFnBody) = httpPostJson("$base/functions", Assets.gson.toJson(manifest))
            assertEquals(201, createFnStatus, "POST /functions failed: $createFnBody")
            val createFnJson = Assets.gson.fromJson(createFnBody, Map::class.java)
            val functionId = createFnJson["id"] as String

            // Create a KV resource and assign ownership to the function
            val resReq = mapOf(
                "type" to "kv",
                "owners" to listOf(functionId)
            )
            val (createResStatus, createResBody) = httpPostJson("$base/resources", Assets.gson.toJson(resReq))
            assertEquals(201, createResStatus, "POST /resources failed: $createResBody")
            val createResJson = Assets.gson.fromJson(createResBody, Map::class.java)
            assertEquals("kv", createResJson["type"])

            // Invoke the function and assert it uses the KV
            val (invokeStatus, invokeBody) = httpPostJson("$base/invoke/$functionId", "{}")
            assertEquals(200, invokeStatus, "POST /invoke/{id} failed: $invokeBody")
            val invokeJson = Assets.gson.fromJson(invokeBody, Map::class.java)
            assertEquals("bar", invokeJson["foo"], "Expected KV round-trip via platform.kv. Body: $invokeBody")
        } finally {
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

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { p ->
                try { Files.deleteIfExists(p) } catch (_: Exception) {}
            }
    }
}
