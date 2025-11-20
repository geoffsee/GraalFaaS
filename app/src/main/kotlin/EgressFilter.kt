package ltd.gsio.app

import ltd.gsio.utils.blocklist.IpUtils
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference

/**
 * Memory-mapped egress IP blocklist with fail-closed semantics and hot-reload.
 *
 * Binary format: 'RNG1' magic (4 bytes), count N (int, big-endian), then N pairs of
 * 32-bit unsigned integers (start, end), inclusive, sorted and non-overlapping.
 *
 * Lookup uses binary search. While not a Patricia trie, this is compact and fast (< microseconds).
 * This can be upgraded to a radix/PATRICIA layout without changing the API.
 */
object EgressFilter {
    private const val DEFAULT_FILENAME = "blocklist.bin"
    private val stateRef = AtomicReference<State>(State.Missing)
    @Volatile private var lastLoadedMtime: Long = -1L
    @Volatile private var lastLoadedSize: Long = -1L

    private enum class Mode { RANGES, TRIE }

    private sealed interface State {
        data object Missing : State
        data class Loaded(
            val file: Path,
            val map: MappedByteBuffer,
            val mode: Mode,
            // RANGES
            val count: Int = 0,
            val rangesOffset: Int = 0,
            // TRIE
            val rootOffset: Int = 0,
        ) : State
    }

    private fun blocklistPath(): Path {
        val override = System.getProperty("egress.blocklist.file")?.trim()?.takeIf { it.isNotEmpty() }
        return Path.of(override ?: DEFAULT_FILENAME)
    }

    /** Load or reload the blocklist if changed, swapping atomically. */
    @Synchronized
    fun ensureLoaded() {
        val p = blocklistPath()
        try {
            if (!Files.exists(p)) {
                stateRef.set(State.Missing)
                return
            }
            val attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
            val mtime = attrs.lastModifiedTime().toMillis()
            val size = attrs.size()
            if (mtime == lastLoadedMtime && size == lastLoadedSize && stateRef.get() is State.Loaded) return

            FileChannel.open(p, StandardOpenOption.READ).use { ch ->
                val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                map.order(ByteOrder.BIG_ENDIAN)
                if (map.remaining() < 8) throw IOException("blocklist too small")
                val m0 = map.get().toInt().toChar()
                val m1 = map.get().toInt().toChar()
                val m2 = map.get().toInt().toChar()
                val m3 = map.get().toInt().toChar()
                when ("" + m0 + m1 + m2 + m3) {
                    "RNG1" -> {
                        val count = map.int
                        if (count < 0) throw IOException("Negative count")
                        val expectedBytes = 8 + count * 8
                        if (ch.size() < expectedBytes) throw IOException("Truncated blocklist")
                        map.position(0)
                        stateRef.set(State.Loaded(p, map, Mode.RANGES, count = count, rangesOffset = 8))
                    }
                    "TRI1" -> {
                        // reserved (skip)
                        val reserved = map.int
                        // Root at offset 8
                        map.position(0)
                        stateRef.set(State.Loaded(p, map, Mode.TRIE, rootOffset = 8))
                    }
                    else -> throw IOException("Unknown blocklist magic: $m0$m1$m2$m3")
                }
                lastLoadedMtime = mtime
                lastLoadedSize = size
            }
        } catch (e: Exception) {
            // Fail-closed: treat as missing/corrupt
            stateRef.set(State.Missing)
        }
    }

    /** true if the IPv4 address (as Int) is blocked. Fail-closed when list missing. */
    fun isBlocked(ip: Int): Boolean {
        // Allow loopback as local intra-process communication; treat as non-egress.
        if ((ip ushr 24) == 127) return false
        val st = stateRef.get()
        if (st !is State.Loaded) return true // fail-closed
        return when (st.mode) {
            Mode.RANGES -> isBlockedRanges(ip, st)
            Mode.TRIE -> isBlockedTrie(ip, st)
        }
    }

    private fun getIntBE(buf: MappedByteBuffer, pos: Int): Int {
        val p = buf.position()
        buf.position(pos)
        val v = buf.int
        buf.position(p)
        return v
    }

    private fun isBlockedRanges(ip: Int, st: State.Loaded): Boolean {
        val map = st.map
        val count = st.count
        val base = st.rangesOffset
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val pos = base + mid * 8
            val start = getIntBE(map, pos)
            val end = getIntBE(map, pos + 4)
            if (ip < start) hi = mid - 1 else if (ip > end) lo = mid + 1 else return true
        }
        return false
    }

    private fun isBlockedTrie(ip: Int, st: State.Loaded): Boolean {
        // Traverse from root; any leaf encountered during walk means blocked prefix matched.
        var nodeOff = st.rootOffset
        var bitIdx = 0
        val buf = st.map
        while (nodeOff != 0 && bitIdx <= 32) {
            val t = getUByte(buf, nodeOff)
            val nodeBitIndex = getUByte(buf, nodeOff + 1)
            val left = getIntBE(buf, nodeOff + 2)
            val right = getIntBE(buf, nodeOff + 6)
            when (t) {
                2 -> return true // leaf => blocked
                1 -> {
                    // Align our traversal to node's bit index to be robust
                    bitIdx = nodeBitIndex
                    if (bitIdx >= 32) return true // Covers /32
                    val bit = (ip ushr (31 - bitIdx)) and 1
                    nodeOff = if (bit == 0) left else right
                    bitIdx++
                }
                else -> return true // unknown node => fail closed
            }
        }
        return false
    }

    private fun getUByte(buf: MappedByteBuffer, pos: Int): Int {
        val p = buf.position()
        buf.position(pos)
        val v = buf.get().toInt() and 0xFF
        buf.position(p)
        return v
    }

    /** Enforce policy: resolve destination and deny if ANY IP blocked. */
    fun enforceUri(uri: URI) {
        ensureLoaded()
        val host = uri.host ?: return // no host (e.g., file:), allow
        val ips: List<Int> = try {
            // If literal IPv4
            if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                listOf(IpUtils.parseIpv4ToInt(host))
            } else {
                InetAddress.getAllByName(host).mapNotNull { addr -> IpUtils.inetAddressToIpv4Int(addr) }
            }
        } catch (e: Exception) {
            // DNS failure; we deny to be safe (fail-closed)
            throw SecurityException("Connection denied: DNS resolution failed for $host", e)
        }
        if (ips.isEmpty()) {
            throw SecurityException("Connection denied: No resolvable IPv4 for $host")
        }
        for (ip in ips) {
            if (isBlocked(ip)) {
                val dotted = IpUtils.intToIpv4(ip)
                throw SecurityException("Connection blocked: destination $host resolves to blocked IP $dotted")
            }
        }
    }

    /** Start a hot-reload daemon that reloads the file atomically if it changes. */
    fun startHotReloader(periodMillis: Long = 60_000L) {
        val t = Thread({
            while (true) {
                try {
                    ensureLoaded()
                } catch (_: Throwable) {
                    // ignore; fail-closed state is handled in ensureLoaded
                }
                try { Thread.sleep(periodMillis) } catch (_: InterruptedException) { return@Thread }
            }
        }, "egress-filter-reloader")
        t.isDaemon = true
        t.start()
    }
}
