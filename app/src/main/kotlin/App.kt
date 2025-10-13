package ltd.gsio.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

@Suppress("unused")
fun main() = main(emptyArray())

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        // Default demo behavior
        println("--- GraalFaaS Demo ---")
        val jsSource = loadResource("/functions/js/hello.js")
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "js",
                sourceCode = jsSource,
                functionName = "handler",
                event = mapOf("name" to "World"),
                timeoutMillis = 5_000
            )
        )
        println("JavaScript handler result: $result")
        return
    }

    when (val cmd = args[0]) {
        "upload" -> {
            if (args.size < 2) {
                System.err.println("Usage: ./gradlew run --args=\"upload <manifest.jsonc>\"")
                return
            }
            val manifestPath = java.nio.file.Path.of(args[1])
            try {
                val manifest = Assets.readManifest(manifestPath)
                val asset = Assets.toAsset(java.nio.file.Path.of("."), manifest)
                Assets.save(asset)
                println("Uploaded function '${'$'}{asset.id}' -> ${'$'}{Assets.assetPath(asset.id).toAbsolutePath()}")
            } catch (t: Throwable) {
                System.err.println("Upload failed: ${'$'}{t.message}")
                t.printStackTrace()
            }
        }
        "serve" -> {
            var port = 8080
            // rudimentary arg parse: serve --port 9090
            var i = 1
            while (i < args.size) {
                when (args[i]) {
                    "--port" -> {
                        if (i + 1 >= args.size) { System.err.println("--port requires a value"); return }
                        port = args[i + 1].toIntOrNull() ?: run { System.err.println("Invalid port: ${'$'}{args[i+1]}"); return }
                        i += 2
                    }
                    else -> { System.err.println("Unknown option: ${'$'}{args[i]}"); return }
                }
            }
            startServer(port)
        }
        "list" -> {
            val list = Assets.list()
            if (list.isEmpty()) {
                println("No functions uploaded.")
            } else {
                for (a in list) {
                    println("- ${'$'}{a.id} [lang=${'$'}{a.languageId}, fn=${'$'}{a.functionName}, esm=${'$'}{a.jsEvalAsModule}]")
                }
            }
        }
        else -> {
            System.err.println("Unknown command: ${'$'}cmd")
            System.err.println("Commands: upload, serve, list")
        }
    }
}

private fun loadResource(path: String): String {
    val stream = {}::class.java.getResourceAsStream(path)
        ?: error("Resource not found: $path")
    return stream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
}

private fun startServer(port: Int) {
    Assets.ensureDirs()
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/health", HttpHandler { exchange ->
        val resp = "OK"
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(resp.toByteArray()) }
    })

    server.createContext("/invoke") { exchange ->
        try {
            if (exchange.requestMethod.uppercase() != "POST") {
                sendJson(exchange, 405, mapOf("error" to "Method Not Allowed"))
                return@createContext
            }
            val path = exchange.requestURI.path // /invoke/{id}
            val parts = path.split('/')
            val id = parts.getOrNull(2)
            if (id.isNullOrBlank()) {
                sendJson(exchange, 400, mapOf("error" to "Missing function id in path /invoke/{id}"))
                return@createContext
            }
            val asset = Assets.load(id)
            if (asset == null) {
                sendJson(exchange, 404, mapOf("error" to "Function not found: $id"))
                return@createContext
            }
            val body = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
            val event: Map<String, Any?> = if (body.isBlank()) {
                emptyMap()
            } else {
                try {
                    Assets.mapper.readValue(body, Assets.mapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java))
                } catch (_: Exception) { emptyMap() }
            }
            val result = PolyglotFaaS.invoke(
                PolyglotFaaS.InvocationRequest(
                    languageId = asset.languageId,
                    sourceCode = asset.sourceCode,
                    functionName = asset.functionName,
                    event = event,
                    dependencies = asset.dependencies,
                    jsEvalAsModule = asset.jsEvalAsModule,
                    timeoutMillis = 5_000
                )
            )
            sendJson(exchange, 200, result)
        } catch (t: Throwable) {
            sendJson(exchange, 500, mapOf("error" to (t.message ?: t::class.java.simpleName)))
        }
    }

    server.executor = Executors.newCachedThreadPool()
    server.start()
    println("HTTP server started on http://localhost:$port (POST /invoke/{id})")
}

private fun sendJson(exchange: HttpExchange, status: Int, body: Any?) {
    val bytes = Assets.mapper.writeValueAsBytes(body)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
