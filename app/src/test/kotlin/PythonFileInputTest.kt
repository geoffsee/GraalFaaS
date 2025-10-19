package ltd.gsio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PythonFileInputTest {
    @Test
    fun `python handler can read staged file input`() {
        val pySource = """
            def handler(event):
                files = event.get('files', []) or []
                if not files:
                    return -1
                f0 = files[0]
                path = f0.get('path')
                with open(path, 'rb') as fh:
                    data = fh.read()
                    return len(data)
        """.trimIndent()

        val payload = "a,b,c\n1,2,3\n".toByteArray()
        val result = PolyglotFaaS.invoke(
            PolyglotFaaS.InvocationRequest(
                languageId = "python",
                sourceCode = pySource,
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
