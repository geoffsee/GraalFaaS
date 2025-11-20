package ltd.gsio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RubyFileInputTest {
    @Test
    fun `ruby handler can read staged file input`() {
        val rbSource = """
            def handler(event)
              files = event['files'] || []
              return -1 if files.nil? || files.empty?
              f0 = files[0]
              path = f0['path']
              data = File.binread(path)
              data.bytesize
            end
        """.trimIndent()

        val payload = "a,b,c\n1,2,3\n".toByteArray()
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "ruby",
                sourceCode = rbSource,
                files = listOf(
                    PolyglotFaaS.FileInput(
                        name = "data.csv",
                        contentType = "text/csv",
                        bytes = payload
                    )
                )
            )
        )

        assertTrue(result is Int, "Result should be an Int, was: ${'$'}{result?.javaClass}")
        assertEquals(payload.size, result as Int)
    }
}
