package ltd.gsio.app

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class AppIntegrationTest {
    @Test
    fun `cli demo prints expected output`() {
        val originalOut = System.out
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos, true, StandardCharsets.UTF_8)
        try {
            System.setOut(ps)

            // Run the application entrypoint
            main()

            ps.flush()
            val output = baos.toString(StandardCharsets.UTF_8)

            // Basic end-to-end assertions: banner and result line
            assertTrue(output.contains("--- GraalFaaS Demo ---"),
                "Output should contain the demo banner. Actual output:\n$output")
            assertTrue(output.contains("JavaScript handler result: {message=Hello, World!}"),
                "Output should contain the handler result for 'World'. Actual output:\n$output")
        } finally {
            System.setOut(originalOut)
            ps.close()
        }
    }
}