package ltd.gsio.utils.blocklist

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * In-memory prefix trie for IPv4 block prefixes. Can be compressed and serialized to TRI1 binary.
 */
class PrefixTrieBuilder {
    private class Node {
        var left: Node? = null
        var right: Node? = null
        var terminal: Boolean = false
    }

    private var root = Node()

    fun addIp(ip: Int) = addPrefix(ip, 32)

    fun addPrefix(ip: Int, maskLen: Int) {
        require(maskLen in 0..32)
        var n = root
        var bit = 0
        while (bit < maskLen) {
            val which = ((ip ushr (31 - bit)) and 1)
            n = if (which == 0) {
                var l = n.left
                if (l == null) { l = Node(); n.left = l }
                l
            } else {
                var r = n.right
                if (r == null) { r = Node(); n.right = r }
                r
            }
            bit++
        }
        n.terminal = true
        // Optional: prune any descendants (blocked by shorter prefix)
        n.left = null
        n.right = null
    }

    /** Compress the trie by removing unary chains without terminals (Patricia-like). */
    private fun compress(node: Node?, depth: Int): CNode? {
        if (node == null) return null
        if (node.terminal) return CNode.Leaf(depth)
        val l = compress(node.left, depth + 1)
        val r = compress(node.right, depth + 1)
        if (l == null && r == null) return null
        if (l != null && r == null) return l
        if (l == null && r != null) return r
        return CNode.Branch(depth, l!!, r!!)
    }

    /** Compressed node for serialization. */
    private sealed class CNode(open val bitIndex: Int) {
        data class Branch(override val bitIndex: Int, val left: CNode, val right: CNode) : CNode(bitIndex)
        data class Leaf(override val bitIndex: Int) : CNode(bitIndex)
    }

    /** Serialize to TRI1 format: 'TRI1' + nodes... Offsets are absolute from file start. */
    fun writeBinaryAtomically(target: Path) {
        val compressed = compress(root, 0) ?: CNode.Leaf(0)
        // First pass: compute size and assign offsets
        val nodes = ArrayList<CNode>()
        fun collect(n: CNode) {
            nodes.add(n)
            if (n is CNode.Branch) { collect(n.left); collect(n.right) }
        }
        collect(compressed)
        val headerSize = 8 // TRI1 + reserved
        val nodeSize = 1 + 1 + 4 + 4 // type, bitIndex, leftOff, rightOff
        val size = headerSize + nodes.size * nodeSize
        val offsets = HashMap<CNode, Int>(nodes.size * 2)
        var off = headerSize
        for (n in nodes) { offsets[n] = off; off += nodeSize }

        // Second pass: write
        val buf = ByteBuffer.allocate(size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put('T'.code.toByte()); buf.put('R'.code.toByte()); buf.put('I'.code.toByte()); buf.put('1'.code.toByte())
        buf.putInt(0) // reserved

        fun writeNode(n: CNode) {
            when (n) {
                is CNode.Leaf -> {
                    buf.put(2) // leaf
                    buf.put(n.bitIndex.toByte())
                    buf.putInt(0)
                    buf.putInt(0)
                }
                is CNode.Branch -> {
                    buf.put(1) // branch
                    buf.put(n.bitIndex.toByte())
                    buf.putInt(offsets[n.left] ?: 0)
                    buf.putInt(offsets[n.right] ?: 0)
                }
            }
        }
        for (n in nodes) writeNode(n)
        buf.flip()

        val dir = target.parent ?: Path.of(".")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "blocklist", ".tri.tmp")
        try {
            Files.write(tmp, buf.array())
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
        }
    }
}
