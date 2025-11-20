package ltd.gsio.utils.blocklist

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Merge IPs/CIDRs from multiple sources into coalesced IPv4 ranges and write a compact binary file.
 * Binary format: 'RNG1' + N (int, big endian) + N pairs of start/end (ints, big endian), inclusive.
 */
class BlocklistMerger {
    private val ranges = ArrayList<IntRange>(1 shl 12)
    private val trie = PrefixTrieBuilder()

    fun addIp(ip: String) {
        try {
            val v = IpUtils.parseIpv4ToInt(ip)
            ranges.add(v..v)
            trie.addIp(v)
        } catch (_: Exception) {
            // ignore invalid
        }
    }

    fun addCidr(cidr: String) {
        try {
            ranges.add(IpUtils.cidrToRange(cidr))
            val parts = cidr.trim().split('/')
            val base = IpUtils.parseIpv4ToInt(parts[0])
            val maskLen = parts[1].toInt()
            trie.addPrefix(base, maskLen)
        } catch (_: Exception) {
            // ignore invalid
        }
    }

    fun addLine(line: String) {
        var s = line.trim()
        if (s.isEmpty() || s.startsWith("#")) return
        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash).trim()
        if (s.isEmpty()) return
        // Tokenize by whitespace or comma
        val token = s.split(' ', '\t', ',', ';').firstOrNull { it.isNotBlank() }?.trim() ?: return
        if (CIDR_REGEX.matches(token)) {
            addCidr(token)
        } else if (IPV4_REGEX.matches(token)) {
            addIp(token)
        }
    }

    fun addStream(input: InputStream) {
        BufferedReader(InputStreamReader(input)).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                addLine(line!!)
            }
        }
    }

    fun addUrl(url: String, timeoutMillis: Long = 20_000) {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMillis))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMillis))
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() in 200..299) {
            resp.body().use { addStream(it) }
        } else {
            throw IllegalStateException("Failed to fetch $url: HTTP ${'$'}{resp.statusCode()}")
        }
    }

    /** Sort and coalesce overlapping/adjacent ranges. */
    fun build(): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        ranges.sortWith(compareBy({ it.first }, { it.last }))
        val out = ArrayList<IntRange>(ranges.size)
        var cur = ranges[0]
        for (i in 1 until ranges.size) {
            val r = ranges[i]
            if (r.first <= cur.last + 1) {
                // merge
                cur = cur.first..maxOf(cur.last, r.last)
            } else {
                out.add(cur)
                cur = r
            }
        }
        out.add(cur)
        return out
    }

    /** Write to a temp file and atomically move into place. */
    fun writeBinaryAtomically(ranges: List<IntRange>, target: Path) {
        val dir = target.parent ?: Path.of(".")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "blocklist", ".bin.tmp")
        try {
            val bufSize = 8 + ranges.size * 8
            val bb = ByteBuffer.allocate(bufSize)
            bb.order(ByteOrder.BIG_ENDIAN)
            bb.put('R'.code.toByte())
            bb.put('N'.code.toByte())
            bb.put('G'.code.toByte())
            bb.put('1'.code.toByte())
            bb.putInt(ranges.size)
            for (r in ranges) {
                bb.putInt(r.first)
                bb.putInt(r.last)
            }
            bb.flip()
            Files.write(tmp, bb.array())
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
        }
    }

    /** Write the compressed trie format (TRI1) atomically to target. */
    fun writeTrieAtomically(target: Path) {
        trie.writeBinaryAtomically(target)
    }

    companion object {
        private val IPV4_REGEX = Regex("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")
        private val CIDR_REGEX = Regex("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}/(?:[0-9]|[12]\\d|3[0-2])$")
    }
}

object BlocklistCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: build-blocklist <outputPath> [--source <urlOrFile>]...\nDefault sources: ET, Spamhaus DROP, FireHOL L1, Blocklist.de")
        }
        val out = Path.of(args.getOrNull(0) ?: "blocklist.bin")
        val merger = BlocklistMerger()
        val sources = mutableListOf<String>()
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--source", "-s" -> { sources += args.getOrNull(i + 1) ?: error("--source requires value"); i += 2 }
                else -> { System.err.println("Unknown arg: ${'$'}{args[i]}"); return }
            }
        }
        if (sources.isEmpty()) {
            sources += listOf(
                "https://rules.emergingthreats.net/fwrules/emerging-Block-IPs.txt",
                "https://www.spamhaus.org/drop/drop.txt",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset",
                "https://lists.blocklist.de/lists/all.txt"
            )
        }
        for (src in sources) {
            if (src.startsWith("http://") || src.startsWith("https://")) {
                merger.addUrl(src)
            } else {
                Files.newInputStream(Path.of(src)).use { merger.addStream(it) }
            }
        }
        val built = merger.build()
        // Write radix/PATRICIA trie format per requirements
        merger.writeTrieAtomically(out)
        println("Wrote compressed trie to ${'$'}out (ranges parsed: ${'$'}{built.size})")
    }
}
