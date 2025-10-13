package ltd.gsio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.charset.StandardCharsets

class PolyglotFaasTest {
    @Test
    fun `javascript hello handler returns greeting`() {
        val jsSource = loadResource("/functions/js/hello.js")
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "js",
                sourceCode = jsSource,
                functionName = "handler",
                event = mapOf("name" to "Tester")
            )
        )

        // Expecting a map-like result with message
        assertTrue(result is Map<*, *>, "Result should be a Map, was: ${result?.javaClass}")
        assertEquals("Hello, Tester!", (result as Map<*, *>) ["message"])
    }

    @Test
    fun `javascript handler with dependency returns greeting`() {
        val main = loadResource("/functions/js/hello-dep.js")
        val dep = loadResource("/functions/js/lib/greeter.js")
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "js",
                sourceCode = main,
                functionName = "handler",
                event = mapOf("name" to "DepUser"),
                dependencies = mapOf("greeter" to dep)
            )
        )
        assertTrue(result is Map<*, *>, "Result should be a Map, was: ${result?.javaClass}")
        assertEquals("Hello, DepUser!", (result as Map<*, *>)["message"])
    }

    @Test
    fun `javascript ES module handler returns greeting`() {
        val jsSource = loadResource("/functions/js/hello-esm.mjs")
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "js",
                sourceCode = jsSource,
                functionName = "handler",
                event = mapOf("name" to "ESM"),
                jsEvalAsModule = true
            )
        )
        assertTrue(result is Map<*, *>, "Result should be a Map, was: ${result?.javaClass}")
        assertEquals("Hello, ESM!", (result as Map<*, *>)["message"])
    }

    @Test
    fun `python hello handler returns greeting`() {
        val pySource = loadResource("/functions/py/hello.py")
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "python",
                sourceCode = pySource,
                functionName = "handler",
                event = mapOf("name" to "PyUser")
            )
        )
        assertTrue(result is String, "Result should be a String, was: ${result?.javaClass}")
        assertEquals("Hello, PyUser!", result as String)
    }

    private fun loadResource(path: String): String {
        val stream = {}::class.java.getResourceAsStream(path)
            ?: error("Resource not found: $path")
        return stream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
    }
}
