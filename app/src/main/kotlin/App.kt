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
        log("INFO", "Starting GraalFaaS Demo")
        println("--- GraalFaaS Demo ---")
        val jsSource = loadResource("/functions/js/hello.js")
        log("DEBUG", "Loaded JS source: ${jsSource.take(50)}...")
        val startTime = System.currentTimeMillis()
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "js",
                sourceCode = jsSource,
                functionName = "handler",
                event = mapOf("name" to "World"),
                timeoutMillis = 5_000
            )
        )
        val duration = System.currentTimeMillis() - startTime
        log("INFO", "JavaScript handler invoked successfully in ${duration}ms")
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
    log("DEBUG", "Loading resource: $path")
    val stream = {}::class.java.getResourceAsStream(path)
        ?: error("Resource not found: $path")
    val content = stream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
    log("DEBUG", "Loaded resource $path (${content.length} bytes)")
    return content
}

fun createServer(port: Int): HttpServer {
    log("INFO", "Creating HTTP server on port $port")
    Assets.ensureDirs()
    Resources.ensureDirs()
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/health", HttpHandler { exchange ->
        val requestId = generateRequestId()
        log("DEBUG", "[$requestId] GET /health from ${exchange.remoteAddress}")
        val resp = "OK"
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(resp.toByteArray()) }
        log("DEBUG", "[$requestId] Health check responded: 200 OK")
    })

    server.createContext("/invoke") { exchange ->
        val requestId = generateRequestId()
        val startTime = System.currentTimeMillis()
        try {
            log("INFO", "[$requestId] ${exchange.requestMethod} ${exchange.requestURI.path} from ${exchange.remoteAddress}")

            if (exchange.requestMethod.uppercase() != "POST") {
                log("WARN", "[$requestId] Method not allowed: ${exchange.requestMethod}")
                sendJson(exchange, 405, mapOf("error" to "Method Not Allowed"))
                return@createContext
            }
            val path = exchange.requestURI.path // /invoke/{id}
            val parts = path.split('/')
            val id = parts.getOrNull(2)
            if (id.isNullOrBlank()) {
                log("WARN", "[$requestId] Missing function ID in path")
                sendJson(exchange, 400, mapOf("error" to "Missing function id in path /invoke/{id}"))
                return@createContext
            }
            log("DEBUG", "[$requestId] Loading function: $id")
            val asset = Assets.load(id)
            if (asset == null) {
                log("WARN", "[$requestId] Function not found: $id")
                sendJson(exchange, 404, mapOf("error" to "Function not found: $id"))
                return@createContext
            }
            val body = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
            log("DEBUG", "[$requestId] Request body: ${body.take(200)}${if (body.length > 200) "..." else ""}")
            val event: Map<String, Any?> = if (body.isBlank()) {
                emptyMap()
            } else {
                try {
                    @Suppress("UNCHECKED_CAST")
                    Assets.gson.fromJson(body, Map::class.java) as Map<String, Any?>
                } catch (_: Exception) { emptyMap() }
            }
            log("INFO", "[$requestId] Invoking function: $id (lang=${asset.languageId}, fn=${asset.functionName})")
            val invokeStartTime = System.currentTimeMillis()
            val platform = Resources.platformForFunction(id)
            log("DEBUG", "[$requestId] platform for function $id: present=${platform != null}, hasKv=${platform?.hasKv()}" )
            val result = PolyglotFaaS.invoke(
                PolyglotFaaS.InvocationRequest(
                    languageId = asset.languageId,
                    sourceCode = asset.sourceCode,
                    functionName = asset.functionName,
                    event = event,
                    dependencies = asset.dependencies,
                    jsEvalAsModule = asset.jsEvalAsModule,
                    timeoutMillis = 5_000,
                    platform = platform
                )
            )
            val invokeDuration = System.currentTimeMillis() - invokeStartTime
            log("INFO", "[$requestId] Function execution completed in ${invokeDuration}ms")
            sendJson(exchange, 200, result)
            val totalDuration = System.currentTimeMillis() - startTime
            log("INFO", "[$requestId] Request completed: 200 OK (total: ${totalDuration}ms)")
        } catch (t: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            log("ERROR", "[$requestId] Request failed after ${duration}ms: ${t.message}", t)
            sendJson(exchange, 500, mapOf("error" to (t.message ?: t::class.java.simpleName)))
        }
    }

    // Create or list functions
    server.createContext("/functions") { exchange ->
        val requestId = generateRequestId()
        val startTime = System.currentTimeMillis()
        try {
            log("INFO", "[$requestId] ${exchange.requestMethod} ${exchange.requestURI.path} from ${exchange.remoteAddress}")

            when (exchange.requestMethod.uppercase()) {
                "POST" -> {
                    val body = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
                    if (body.isBlank()) {
                        log("WARN", "[$requestId] Empty request body")
                        sendJson(exchange, 400, mapOf("error" to "Empty request body"))
                        return@createContext
                    }
                    log("DEBUG", "[$requestId] Creating function with body: ${body.take(200)}${if (body.length > 200) "..." else ""}")
                    // Accept either full UploadManifest or direct FunctionAsset for simplicity
                    val manifest = try {
                        Assets.gson.fromJson(body, Assets.UploadManifest::class.java)
                    } catch (e: Exception) {
                        log("WARN", "[$requestId] Invalid JSON in request: ${e.message}")
                        sendJson(exchange, 400, mapOf("error" to "Invalid JSON: ${e.message}"))
                        return@createContext
                    }
                    try {
                        log("DEBUG", "[$requestId] Processing manifest for function: ${manifest.id}")
                        val asset = Assets.toAsset(java.nio.file.Path.of("."), manifest)
                        Assets.save(asset)
                        log("INFO", "[$requestId] Function created successfully: ${asset.id} (lang=${asset.languageId})")
                        sendJson(exchange, 201, mapOf(
                            "id" to asset.id,
                            "languageId" to asset.languageId,
                            "functionName" to asset.functionName,
                            "jsEvalAsModule" to asset.jsEvalAsModule,
                            "dependencies" to asset.dependencies.keys
                        ))
                        val duration = System.currentTimeMillis() - startTime
                        log("INFO", "[$requestId] Request completed: 201 Created (${duration}ms)")
                    } catch (e: IllegalArgumentException) {
                        log("WARN", "[$requestId] Invalid manifest: ${e.message}")
                        sendJson(exchange, 400, mapOf("error" to e.message))
                    } catch (t: Throwable) {
                        log("ERROR", "[$requestId] Function creation failed: ${t.message}", t)
                        sendJson(exchange, 500, mapOf("error" to (t.message ?: t::class.java.simpleName)))
                    }
                }
                "GET" -> {
                    val list = Assets.list()
                    log("INFO", "[$requestId] Listing ${list.size} function(s)")
                    val response = list.map {
                        mapOf(
                            "id" to it.id,
                            "languageId" to it.languageId,
                            "functionName" to it.functionName,
                            "jsEvalAsModule" to it.jsEvalAsModule,
                        )
                    }
                    sendJson(exchange, 200, response)
                    val duration = System.currentTimeMillis() - startTime
                    log("INFO", "[$requestId] Request completed: 200 OK (${duration}ms)")
                }
                else -> {
                    log("WARN", "[$requestId] Method not allowed: ${exchange.requestMethod}")
                    sendJson(exchange, 405, mapOf("error" to "Method Not Allowed"))
                }
            }
        } catch (t: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            log("ERROR", "[$requestId] Request failed after ${duration}ms: ${t.message}", t)
            sendJson(exchange, 500, mapOf("error" to (t.message ?: t::class.java.simpleName)))
        }
    }

    // Create or list resources
    server.createContext("/resources") { exchange ->
        val requestId = generateRequestId()
        val startTime = System.currentTimeMillis()
        try {
            log("INFO", "[$requestId] ${exchange.requestMethod} ${exchange.requestURI.path} from ${exchange.remoteAddress}")

            when (exchange.requestMethod.uppercase()) {
                "POST" -> {
                    val body = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
                    if (body.isBlank()) {
                        log("WARN", "[$requestId] Empty request body")
                        sendJson(exchange, 400, mapOf("error" to "Empty request body"))
                        return@createContext
                    }
                    val req = try {
                        Assets.gson.fromJson(body, Resources.CreateRequest::class.java)
                    } catch (e: Exception) {
                        log("WARN", "[$requestId] Invalid JSON in request: ${e.message}")
                        sendJson(exchange, 400, mapOf("error" to "Invalid JSON: ${e.message}"))
                        return@createContext
                    }
                    if (req.type.isBlank()) {
                        sendJson(exchange, 400, mapOf("error" to "'type' is required"))
                        return@createContext
                    }
                    val rec = Resources.create(req)
                    log("INFO", "[$requestId] Resource created: ${rec.id} (type=${rec.type}) owners=${rec.owners}")
                    sendJson(exchange, 201, mapOf(
                        "id" to rec.id,
                        "type" to rec.type,
                        "owners" to rec.owners
                    ))
                }
                "GET" -> {
                    val list = Resources.list()
                    val response = list.map { mapOf("id" to it.id, "type" to it.type, "owners" to it.owners) }
                    sendJson(exchange, 200, response)
                }
                else -> {
                    log("WARN", "[$requestId] Method not allowed: ${exchange.requestMethod}")
                    sendJson(exchange, 405, mapOf("error" to "Method Not Allowed"))
                }
            }
        } catch (t: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            log("ERROR", "[$requestId] /resources failed after ${duration}ms: ${t.message}", t)
            sendJson(exchange, 500, mapOf("error" to (t.message ?: t::class.java.simpleName)))
        }
    }

    // Attach owner to a resource: POST /resources/{id}/owners with body { "functionId": "..." }
    server.createContext("/resources/") { exchange ->
        val requestId = generateRequestId()
        val startTime = System.currentTimeMillis()
        try {
            val path = exchange.requestURI.path // /resources/{id}/owners
            val parts = path.trimEnd('/').split('/')
            if (parts.size < 4 || parts[3] != "owners") {
                sendJson(exchange, 404, mapOf("error" to "Not Found"))
                return@createContext
            }
            val resId = parts[2]
            if (exchange.requestMethod.uppercase() != "POST") {
                sendJson(exchange, 405, mapOf("error" to "Method Not Allowed"))
                return@createContext
            }
            val body = exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
            val json = try { Assets.gson.fromJson(body, Map::class.java) as Map<String, Any?> } catch (e: Exception) {
                sendJson(exchange, 400, mapOf("error" to "Invalid JSON: ${e.message}")); return@createContext
            }
            val fnId = json["functionId"] as? String
            if (fnId.isNullOrBlank()) { sendJson(exchange, 400, mapOf("error" to "functionId is required")); return@createContext }
            val rec = Resources.attachOwner(resId, fnId)
            sendJson(exchange, 200, mapOf("id" to rec.id, "type" to rec.type, "owners" to rec.owners))
        } catch (t: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            log("ERROR", "[$requestId] /resources/:id/owners failed after ${duration}ms: ${t.message}", t)
            sendJson(exchange, 500, mapOf("error" to (t.message ?: t::class.java.simpleName)))
        }
    }

    return server
}

private fun startServer(port: Int) {
    val server = createServer(port)
    server.executor = Executors.newCachedThreadPool()
    log("INFO", "Starting HTTP server with thread pool executor")
    server.start()
    val boundPort = server.address.port
    log("INFO", "HTTP server successfully started on port $boundPort")
    println("HTTP server started on http://localhost:$boundPort (POST /invoke/{id}, POST /functions, GET /functions)")
    log("INFO", "Available endpoints: /health, /invoke/{id}, /functions, /resources, /resources/{id}/owners")
}

private var requestCounter = java.util.concurrent.atomic.AtomicLong(0)
private fun generateRequestId(): String {
    return "req-${System.currentTimeMillis()}-${requestCounter.incrementAndGet()}"
}

private fun sendJson(exchange: HttpExchange, status: Int, body: Any?) {
    val json = Assets.gson.toJson(body)
    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
    log("DEBUG", "Sent JSON response: $status (${bytes.size} bytes)")
}

private fun log(level: String, message: String, throwable: Throwable? = null) {
    val timestamp = java.time.Instant.now().toString()
    val logMessage = "[$timestamp] [$level] $message"
    when (level) {
        "ERROR", "WARN" -> System.err.println(logMessage)
        else -> println(logMessage)
    }
    throwable?.let {
        System.err.println("  Exception: ${it::class.java.simpleName}: ${it.message}")
    }
}
