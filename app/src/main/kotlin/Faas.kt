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
    data class InvocationRequest(
        val languageId: String, // e.g. "js"
        val sourceCode: String,
        val functionName: String = "handler",
        val event: Map<String, Any?> = emptyMap(),
        // Optional in-memory dependencies: module name -> source code.
        // For JavaScript, these can be required via require('<moduleName>').
        val dependencies: Map<String, String> = emptyMap(),
        // JavaScript: Evaluate source as an ES module instead of a classic script.
        val jsEvalAsModule: Boolean = false,
        // Host-side timeout for this invocation in milliseconds. Null or <= 0 means no timeout.
        val timeoutMillis: Long? = null
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
                        val eventLiteral = toPythonDictLiteral(request.event)
                        ctx.eval(
                            "python",
                            """
                            import polyglot
                            def __faas_invoke__():
                                return ${request.functionName}(${eventLiteral})
                            polyglot.export_value('__faas_invoke__', __faas_invoke__)
                            """.trimIndent()
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
                } else {
                    ctx.getBindings(request.languageId).getMember(request.functionName)
                }
                if (fn == null || !fn.canExecute()) throw FunctionNotFoundException(request.languageId, request.functionName)

                if (request.languageId == "python") {
                    val resultPy: Value = fn.execute()
                    return@use toHost(resultPy)
                }

                val result: Value = fn.execute(request.event)
                // Eagerly convert the result into plain host data structures so it survives after Context close
                return@use toHost(result)
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
            } else {
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
}
