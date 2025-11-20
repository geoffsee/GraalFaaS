package ltd.gsio.app

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ltd.gsio.utils.blocklist.BlocklistMerger
import java.nio.file.Files
import java.nio.file.Path

class EgressFilterTest {
    @Test
    fun `blocked literal IPv4 is denied before connect`() {
        // Build a minimal blocklist that blocks 203.0.113.7/32 (TEST-NET-3 range)
        val tmp = Files.createTempFile("blocklist", ".bin")
        try {
            val merger = BlocklistMerger()
            merger.addCidr("203.0.113.7/32")
            merger.writeTrieAtomically(tmp)

            System.setProperty("egress.blocklist.file", tmp.toAbsolutePath().toString())
            // Force a reload
            EgressFilter.ensureLoaded()

            val net = VirtualNet(connectTimeoutMillis = 100, requestTimeoutMillis = 100)
            assertFailsWith<SecurityException> {
                net.http("GET", "http://203.0.113.7/", null, emptyMap())
            }
        } finally {
            try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
        }
    }

    @Test
    fun `loopback stays allowed even if blocklist missing (non-egress)`() {
        System.setProperty("egress.blocklist.file", "/nonexistent-blocklist.bin")
        // This should not throw because 127.0.0.1 is loopback and treated as non-egress
        EgressFilter.enforceUri(java.net.URI.create("http://127.0.0.1:12345"))
        assertTrue(true)
    }
}
