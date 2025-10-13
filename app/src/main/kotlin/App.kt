package ltd.gsio.app

import java.nio.charset.StandardCharsets

fun main() {
    println("--- GraalFaaS Demo ---")

    val jsSource = loadResource("/functions/js/hello.js")
    val result = PolyglotFaaS.invoke(
        PolyglotFaaS.InvocationRequest(
            languageId = "js",
            sourceCode = jsSource,
            functionName = "handler",
            event = mapOf("name" to "World")
        )
    )

    println("JavaScript handler result: $result")
}

private fun loadResource(path: String): String {
    val stream = {}::class.java.getResourceAsStream(path)
        ?: error("Resource not found: $path")
    return stream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
}
