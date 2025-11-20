package ltd.gsio.app

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.Source

/**
 * A minimal polyglot Function-as-a-Service (FaaS) invoker backed by GraalVM.
 *
 * Contract for guest functions:
 * - Define a top-level function with the given [functionName] (default: "handler").
 * - The function should accept a single argument (the event), which is typically a map/object.
 * - The function should return a value that can be marshalled back to the host (e.g., map/object, string, number, etc.).
 *
 * Example (JavaScript):
 *   function handler(event) { return { message: `Hello, ${event.name}!` }; }
 */
object PolyglotFaaS {
    data class FileInput(
        val name: String,
        val contentType: String? = null,
        val bytes: ByteArray
    )

    data class InvocationRequest(
        val languageId: String, // e.g. "js"
        val sourceCode: String,
        val functionName: String = "handler",
        val event: Map<String, Any?> = emptyMap(),
        // Optional user-provided file inputs to be staged on disk for guest access (read-only).
        val files: List<FileInput> = emptyList(),
        // Optional in-memory dependencies: module name -> source code.
        // For JavaScript, these can be required via require('<moduleName>').
        val dependencies: Map<String, String> = emptyMap(),
        // JavaScript: Evaluate source as an ES module instead of a classic script.
        val jsEvalAsModule: Boolean = false,
        // Host-side timeout for this invocation in milliseconds. Null or <= 0 means no timeout.
        val timeoutMillis: Long? = null,
        // Enable simple virtualized networking (fetch in JS, net.http in Python)
        val enableNetwork: Boolean = false
    )

    class FunctionNotFoundException(languageId: String, functionName: String) :
        RuntimeException("Function '$functionName' not found or not executable in language '$languageId'.")

    class InvocationTimeoutException(timeoutMillis: Long) :
        RuntimeException("Invocation timed out after ${timeoutMillis}ms")

    /**
     * Short-lived worker pool for executing invocations with timeouts.
     * Uses a SynchronousQueue with 0 core threads and allows threads to time out so idle workers are recycled.
     */
    private object WorkerPool {
        private val maxThreads: Int = (Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
        private val threadFactory = java.util.concurrent.ThreadFactory { r ->
            val t = Thread(r, "faas-worker-${'$'}{WORKER_ID.incrementAndGet()}")
            t.isDaemon = true
            t
        }
        private val WORKER_ID = java.util.concurrent.atomic.AtomicInteger(0)
        val executor: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
            0,
            maxThreads,
            30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
            threadFactory
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    /**
     * Invoke a guest function inside a worker thread with an optional timeout.
     * A fresh polyglot Context is still created per task for isolation.
     */
    fun invoke(request: InvocationRequest): Any? {
        val task = java.util.concurrent.Callable { doInvoke(request) }
        val future = WorkerPool.executor.submit(task)
        val timeout = request.timeoutMillis ?: 0L
        return try {
            if (timeout > 0) future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS) else future.get()
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            throw InvocationTimeoutException(timeout)
        } catch (e: java.lang.InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            // Unwrap the cause for cleaner error propagation
            val cause = e.cause
            if (cause is RuntimeException) throw cause
            if (cause != null) throw RuntimeException(cause)
            throw e
        }
    }

    // Actual invocation logic (unchanged) executed within a worker
    private fun doInvoke(request: InvocationRequest): Any? =
        Context.newBuilder(request.languageId)
            .allowAllAccess(true)
            .option("engine.WarnInterpreterOnly", "false") // reduce noise if JIT unavailable
            .also { builder ->
                if (request.languageId == "js" && request.jsEvalAsModule) {
                    builder.option("js.esm-eval-returns-exports", "true")
                }
            }
            .build().use { ctx ->
                // Stage provided files (if any) into a temporary directory and augment the event with file metadata and paths
                val __stagedTempDir: java.nio.file.Path? = if (request.files.isNotEmpty()) java.nio.file.Files.createTempDirectory("faas-files-") else null
                val __stagedFiles: List<Map<String, Any?>> = if (__stagedTempDir != null) {
                    request.files.map { f ->
                        val safeName = sanitizeFilename(f.name.ifBlank { "file.bin" })
                        val p = __stagedTempDir.resolve(safeName)
                        java.nio.file.Files.write(p, f.bytes)
                        mapOf(
                            "name" to f.name,
                            "contentType" to f.contentType,
                            "path" to p.toAbsolutePath().toString(),
                            "size" to f.bytes.size
                        )
                    }
                } else emptyList()
                val __eventWithFiles: Map<String, Any?> = if (__stagedFiles.isNotEmpty()) {
                    val m = java.util.LinkedHashMap<String, Any?>(request.event.size + 1)
                    m.putAll(request.event)
                    m["files"] = __stagedFiles
                    m
                } else request.event

                try {
                // Optional: install virtualized networking into the guest context
                if (request.enableNetwork) {
                    val net = VirtualNet()
                    // Expose to both JS and Python via bindings/polyglot
                    ctx.polyglotBindings.putMember("__net", net)
                    if (request.languageId == "js") {
                        val jsBindings = ctx.getBindings("js")
                        jsBindings.putMember("__net", net)
                        ctx.eval(
                            "js",
                            (
                                "(function(){\n" +
                                    "  if (typeof globalThis.net === 'undefined') {\n" +
                                    "    globalThis.net = {\n" +
                                    "      http: function(method, url, body, headers){ return __net.http(String(method||'GET'), String(url), body==null?null:String(body), headers||{}); },\n" +
                                    "      get: function(url, headers){ return __net.http('GET', String(url), null, headers||{}); },\n" +
                                    "      post: function(url, body, headers){ return __net.http('POST', String(url), body==null?'':String(body), headers||{}); }\n" +
                                    "    };\n" +
                                    "  }\n" +
                                    "  if (typeof globalThis.fetch === 'undefined') {\n" +
                                    "    const __normalizeHeaders = (h) => {\n" +
                                    "      const out = Object.create(null);\n" +
                                    "      if (!h) return out;\n" +
                                    "      if (typeof h.forEach === 'function') {\n" +
                                    "        h.forEach((v,k) => { out[String(k).toLowerCase()] = String(v); });\n" +
                                    "        return out;\n" +
                                    "      }\n" +
                                    "      if (Array.isArray(h)) {\n" +
                                    "        for (const pair of h) { if (pair && pair.length>=2) out[String(pair[0]).toLowerCase()] = String(pair[1]); }\n" +
                                    "        return out;\n" +
                                    "      }\n" +
                                    "      if (typeof h === 'object') {\n" +
                                    "        for (const k in h) if (Object.prototype.hasOwnProperty.call(h,k)) out[String(k).toLowerCase()] = String(h[k]);\n" +
                                    "      }\n" +
                                    "      return out;\n" +
                                    "    };\n" +
                                    "    globalThis.fetch = function(input, init){\n" +
                                    "      init = init || {};\n" +
                                    "      const url = (typeof input === 'string') ? input : (input && input.url);\n" +
                                    "      const method = (init.method || 'GET');\n" +
                                    "      const headers = __normalizeHeaders(init.headers);\n" +
                                    "      const body = ('body' in init) ? init.body : null;\n" +
                                    "      return new Promise((resolve, reject) => {\n" +
                                    "        try {\n" +
                                    "          const res = __net.http(String(method), String(url), body==null?null:String(body), headers);\n" +
                                    "          const _map = res.headers;\n" +
                                    "          const headersObj = {\n" +
                                    "            get(name){ name = String(name).toLowerCase(); try { if (_map && typeof _map.get === 'function') return _map.get(name) ?? null; } catch(_) {} return (_map && _map[name]) || null; },\n" +
                                    "            has(name){ name = String(name).toLowerCase(); try { if (_map && typeof _map.containsKey === 'function') return !!_map.containsKey(name); } catch(_) {} return !!(_map && (_map[name] !== undefined)); }\n" +
                                    "          };\n" +
                                    "          resolve({\n" +
                                    "            ok: (res.status>=200 && res.status<300),\n" +
                                    "            status: res.status,\n" +
                                    "            headers: headersObj,\n" +
                                    "            url: String(url),\n" +
                                    "            text: () => Promise.resolve(String(res.body)),\n" +
                                    "            json: () => new Promise((resolveJson, rejectJson) => { try { resolveJson(JSON.parse(String(res.body))); } catch(e) { rejectJson(e); } })\n" +
                                    "          });\n" +
                                    "        } catch (e) {\n" +
                                    "          reject(e);\n" +
                                    "        }\n" +
                                    "      });\n" +
                                    "    };\n" +
                                    "  }\n" +
                                    "})();\n"
                            )
                        )
                    } else if (request.languageId == "python") {
                        ctx.eval(
                            "python",
                            (
                                "import polyglot\n" +
                                    "__net = polyglot.import_value('__net')\n" +
                                    "class _Net:\n" +
                                    "    def http(self, method, url, body=None, headers=None):\n" +
                                    "        return __net.http(str(method or 'GET'), str(url), body if body is not None else None, headers or {})\n" +
                                    "    def get(self, url, headers=None):\n" +
                                    "        return __net.http('GET', str(url), None, headers or {})\n" +
                                    "    def post(self, url, body=None, headers=None):\n" +
                                    "        return __net.http('POST', str(url), '' if body is None else body, headers or {})\n" +
                                    "net = _Net()\n"
                            )
                        )
                    } else if (request.languageId == "ruby") {
                        ctx.eval(
                            "ruby",
                            (
                                "class NetF\n" +
                                    "  def initialize\n" +
                                    "    @net = Polyglot.import('__net')\n" +
                                    "  end\n" +
                                    "  def http(method, url, body=nil, headers=nil)\n" +
                                    "    @net.http((method || 'GET').to_s, url.to_s, body.nil? ? nil : body.to_s, headers || {})\n" +
                                    "  end\n" +
                                    "  def get(url, headers=nil)\n" +
                                    "    @net.http('GET', url.to_s, nil, headers || {})\n" +
                                    "  end\n" +
                                    "  def post(url, body=nil, headers=nil)\n" +
                                    "    @net.http('POST', url.to_s, body.nil? ? '' : body, headers || {})\n" +
                                    "  end\n" +
                                    "end\n" +
                                    "\$net = NetF.new\n" +
                                    "def net; \$net; end\n"
                            )
                        )
                    }
                }

                // Optional dependency wiring for JavaScript (simple CommonJS-like loader)
                if (request.languageId == "js" && request.dependencies.isNotEmpty()) {
                    val jsBindings = ctx.getBindings("js")
                    jsBindings.putMember("__deps", request.dependencies)
                    ctx.eval(
                        "js",
                        (
                            "const __moduleCache = Object.create(null);\n" +
                                "globalThis.require = function(name) {\n" +
                                "  if (__moduleCache[name]) return __moduleCache[name].exports;\n" +
                                "  const src = __deps[name];\n" +
                                "  if (typeof src !== 'string') { throw new Error('Module not found: ' + name); }\n" +
                                "  const module = { exports: {} };\n" +
                                "  const exports = module.exports;\n" +
                                "  const fn = new Function('exports','module','require', src);\n" +
                                "  fn(exports, module, require);\n" +
                                "  __moduleCache[name] = module;\n" +
                                "  return module.exports;\n" +
                                "};\n"
                        )
                    )
                }

                // Evaluate the source code first
                val moduleNs: Value? = if (request.languageId == "js" && request.jsEvalAsModule) {
                    val src = Source.newBuilder("js", request.sourceCode, "<handler.mjs>")
                        .mimeType("application/javascript+module")
                        .build()
                    ctx.eval(src)
                } else {
                    // Preload Python dependencies as in-memory modules, if provided
                    if (request.languageId == "python" && request.dependencies.isNotEmpty()) {
                        for ((modName, modSrc) in request.dependencies) {
                            val nameEsc = escapePy(modName)
                            val srcEsc = escapePy(modSrc)
                            ctx.eval(
                                "python",
                                (
                                    "import types, sys\n" +
                                        "_mod = types.ModuleType('" + nameEsc + "')\n" +
                                        "_src = '" + srcEsc + "'\n" +
                                        "exec(_src, _mod.__dict__)\n" +
                                        "sys.modules['" + nameEsc + "'] = _mod\n"
                                )
                            )
                        }
                    }
                    ctx.eval(request.languageId, request.sourceCode)
                    // For Python, prepare a zero-arg trampoline exported to polyglot bindings
                    if (request.languageId == "python") {
                        val eventLiteral = toPythonDictLiteral(__eventWithFiles)
                        ctx.eval(
                            "python",
                            """
                            import polyglot
                            def __faas_invoke__():
                                return ${request.functionName}(${eventLiteral})
                            polyglot.export_value('__faas_invoke__', __faas_invoke__)
                            """.trimIndent()
                        )
                    } else if (request.languageId == "ruby") {
                        val eventLiteralRb = toRubyHashLiteral(__eventWithFiles)
                        ctx.eval(
                            "ruby",
                            (
                                "__faas_invoke__ = -> { ${request.functionName}(${eventLiteralRb}) }\n" +
                                    "Polyglot.export('__faas_invoke__', __faas_invoke__)\n"
                            )
                        )
                    }
                    null
                }

                // Resolve the function from language bindings and execute it
                val fn: Value? = if (request.languageId == "js" && request.jsEvalAsModule) {
                    moduleNs?.getMember(request.functionName)
                } else if (request.languageId == "python") {
                    // Retrieve the exported trampoline from the polyglot bindings
                    ctx.polyglotBindings.getMember("__faas_invoke__")
                } else if (request.languageId == "ruby") {
                    ctx.polyglotBindings.getMember("__faas_invoke__")
                } else {
                    ctx.getBindings(request.languageId).getMember(request.functionName)
                }
                if (fn == null || !fn.canExecute()) throw FunctionNotFoundException(request.languageId, request.functionName)

                if (request.languageId == "python" || request.languageId == "ruby") {
                    val resultPy: Value = fn.execute()
                    return@use toHost(resultPy)
                }

                val result: Value = fn.execute(__eventWithFiles)
                // If JavaScript returns a Promise/thenable, await it by default to avoid breaking async handlers
                if (request.languageId == "js" && isJsThenable(result)) {
                    val resolved: Value = awaitJsPromise(ctx, result)
                    return@use toHost(resolved)
                }
                // Eagerly convert the result into plain host data structures so it survives after Context close
                return@use toHost(result)
                } finally {
                    if (__stagedTempDir != null) {
                        try { deleteRecursively(__stagedTempDir) } catch (_: Throwable) {}
                    }
                }
            }

    private fun toHost(value: Value): Any? {
        if (value.isNull) return null
        if (value.isString) return value.asString()
        if (value.isBoolean) return value.asBoolean()
        if (value.fitsInInt()) return value.asInt()
        if (value.fitsInLong()) return value.asLong()
        if (value.fitsInDouble()) return value.asDouble()
        if (value.isHostObject) return value.asHostObject<Any?>()

        if (value.hasArrayElements()) {
            val size = value.arraySize
            val list = ArrayList<Any?>(size.toInt())
            var i = 0L
            while (i < size) {
                list.add(toHost(value.getArrayElement(i)))
                i++
            }
            return list
        }
        // Treat mapping types first if they expose Ruby/Python-like map API
        if (value.canInvokeMember("keys") && value.canInvokeMember("[]")) {
            val map = LinkedHashMap<String, Any?>()
            try {
                val klist = value.invokeMember("keys")
                if (klist != null && klist.hasArrayElements()) {
                    var i = 0L
                    val size = klist.arraySize
                    while (i < size) {
                        val k = toHost(klist.getArrayElement(i))?.toString() ?: "null"
                        map[k] = toHost(value.invokeMember("[]", k))
                        i++
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
            return map
        }
        if (value.hasMembers()) {
            val map = LinkedHashMap<String, Any?>()
            val keys = value.memberKeys
            if (value.canInvokeMember("__getitem__")) {
                for (key in keys) {
                    try {
                        val item = value.invokeMember("__getitem__", key)
                        map[key] = toHost(item)
                    } catch (t: Throwable) {
                        map[key] = toHost(value.getMember(key))
                    }
                }
            } else if (keys.isNotEmpty()) {
                for (key in keys) {
                    map[key] = toHost(value.getMember(key))
                }
            }
            return map
        }
        // Fallback: string representation
        return value.toString()
    }

    // Build a Python dict literal from a Kotlin/Java map (strings, numbers, booleans, null, nested maps/lists)
    private fun toPythonDictLiteral(map: Map<*, *>?): String {
        if (map == null) return "None"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            val key = escapePy(k?.toString() ?: "null")
            "'${key}': ${toPythonLiteral(v)}"
        }
        return "{${entries}}"
    }

    private fun toPythonListLiteral(list: Iterable<*>?): String {
        if (list == null) return "None"
        return list.joinToString(prefix = "[", postfix = "]", separator = ", ") { toPythonLiteral(it) }
    }

    private fun toPythonLiteral(v: Any?): String = when (v) {
        null -> "None"
        is String -> "'${escapePy(v)}'"
        is Number -> v.toString()
        is Boolean -> if (v) "True" else "False"
        is Map<*, *> -> toPythonDictLiteral(v)
        is Iterable<*> -> toPythonListLiteral(v)
        is Array<*> -> toPythonListLiteral(v.asList())
        else -> "'${escapePy(v.toString())}'"
    }

    private fun escapePy(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
    // Build a Ruby hash literal from a Kotlin/Java map
    private fun toRubyHashLiteral(map: Map<*, *>?): String {
        if (map == null) return "nil"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            val key = (k?.toString() ?: "null")
            "'${escapeRuby(key)}' => ${toRubyLiteral(v)}"
        }
        return "{" + entries + "}"
    }

    private fun toRubyArrayLiteral(list: Iterable<*>?): String {
        if (list == null) return "nil"
        return list.joinToString(prefix = "[", postfix = "]", separator = ", ") { toRubyLiteral(it) }
    }

    private fun toRubyLiteral(v: Any?): String = when (v) {
        null -> "nil"
        is String -> "'${escapeRuby(v)}'"
        is Number -> v.toString()
        is Boolean -> if (v) "true" else "false"
        is Map<*, *> -> toRubyHashLiteral(v)
        is Iterable<*> -> toRubyArrayLiteral(v)
        is Array<*> -> toRubyArrayLiteral(v.asList())
        else -> "'${escapeRuby(v.toString())}'"
    }

    private fun escapeRuby(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
    private fun sanitizeFilename(name: String): String {
        val s = name.replace(Regex("[/\\\\]"), "_").trim()
        return if (s.isBlank()) "file.bin" else s.take(255)
    }

    private fun deleteRecursively(root: java.nio.file.Path) {
        try {
            java.nio.file.Files.walk(root)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { p -> try { java.nio.file.Files.deleteIfExists(p) } catch (_: Throwable) {} }
        } catch (_: Throwable) {
            // ignore
        }
    }
}

    private fun isJsThenable(value: Value?): Boolean {
        if (value == null) return false
        return try {
            value.hasMember("then") && value.getMember("then").canExecute()
        } catch (_: Throwable) {
            false
        }
    }

    private fun awaitJsPromise(ctx: Context, promise: Value): Value {
        val latch = java.util.concurrent.CountDownLatch(1)
        val ref = java.util.concurrent.atomic.AtomicReference<Value>()
        val errRef = java.util.concurrent.atomic.AtomicReference<Throwable?>()

        val onFulfilled = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
            try {
                if (args.isNotEmpty()) ref.set(args[0]) else ref.set(null)
            } finally {
                latch.countDown()
            }
            null
        }
        val onRejected = org.graalvm.polyglot.proxy.ProxyExecutable { args ->
            try {
                val reason = if (args.isNotEmpty()) args[0] else null
                errRef.set(RuntimeException(reason?.toString() ?: "Promise rejected"))
            } finally {
                latch.countDown()
            }
            null
        }

        // Attach handlers
        try {
            promise.invokeMember("then", onFulfilled, onRejected)
        } catch (t: Throwable) {
            throw RuntimeException("Failed to attach then/catch to JS Promise: ${t.message}", t)
        }

        // Pump the JS engine microtask queue until the promise settles.
        // We periodically enter JS to give the engine a chance to process queued jobs.
        while (!latch.await(1, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            try {
                ctx.eval("js", "(void 0)")
            } catch (_: Throwable) {
                // ignore: empty eval used to yield back into JS
            }
        }

        errRef.get()?.let { throw it }
        return ref.get()
    }
